#!/usr/bin/env python3
"""
Builds app/src/main/assets/printers/catalog.json from TiMini-Print's printer catalog.

TiMini-Print (https://github.com/Dejniel/TiMini-Print) is Copyright 2026 Daniel Banecki and licensed under the
Apache License 2.0. Its data files (printer_models.json, printer_profiles.json, printer_paper_presets.json,
origins.json) are converted here into a smaller file that only keeps the printer families Token Printer
can drive. See app/src/main/assets/printers/NOTICE.txt.

Usage: python3 tools/build_printer_catalog.py /path/to/TiMini-Print/timiniprint/data
"""
import json
import os
import sys

SUPPORTED = {"tiny", "v5g", "v5x", "funny_lx", "phomemo_esc"}
TINY_VARIANTS = {None, "line_eight"}
PHOMEMO_VARIANTS = {"m02", "m02s", "m02x", "t02", "m02_pro"}
MAX_WIDTH = 576


def levels(block, mode="image"):
    if not block:
        return None
    lv = block.get(mode) or block.get("image")
    if not lv:
        return None
    return [lv.get("low", 0), lv.get("middle", 0), lv.get("high", 0)]


def main(data_dir, out_path):
    load = lambda n: json.load(open(os.path.join(data_dir, n), encoding="utf-8"))
    models = load("printer_models.json")
    profiles = {p["profile_key"]: p for p in load("printer_profiles.json")}
    presets = load("printer_paper_presets.json")
    origins = load("origins.json")

    out = []
    skipped = {}
    for m in models:
        p = profiles[m["profile_key"]]
        proto = p["protocol_default"]
        family = proto["type"]
        variant = proto.get("variant") or proto.get("packets_type")
        if family == "funny_lx":
            variant = None
        reason = None
        if family not in SUPPORTED:
            reason = "family " + family
        elif family == "tiny" and variant not in TINY_VARIANTS:
            reason = "tiny variant " + str(variant)
        elif family == "phomemo_esc" and variant not in PHOMEMO_VARIANTS:
            reason = "phomemo variant " + str(variant)
        preset = presets[p["paper_presets"][0]]
        width = preset["paper_width_px"]
        if reason is None and (width > MAX_WIDTH or width % 8 or width < 200):
            reason = "width %d" % width
        if reason:
            skipped[m["model_key"]] = reason
            continue

        density = None
        preset_key = m.get("profile_runtime_preset_key")
        for rp in p.get("runtime_presets") or []:
            if rp.get("key") == preset_key and rp.get("density"):
                density = levels(rp["density"])
        if density is None:
            density = levels((p.get("print_defaults") or {}).get("density"))
        pd = p.get("print_defaults") or {}
        speed = (pd.get("speed") or {}).get("image")

        detections = []
        for d in m.get("detections", []):
            rule = {k: d[k] for k in (
                "exact_names", "prefixes", "substrings", "suffixes", "excluded_prefixes",
                "manufacturer_data_suffixes", "manufacturer_data_lengths", "name_pattern", "all_of",
            ) if k in d and d[k] not in (None, [], False)}
            for k in ("mac_prefixes", "mac_suffixes", "excluded_mac_suffixes"):
                if d.get(k):
                    rule[k] = d[k]
            detections.append(rule)
        if not detections:
            skipped[m["model_key"]] = "no detection rules"
            continue

        names = []
        for d in m.get("detections", []):
            for n in d.get("marketing_names", []) + d.get("exact_names", []) + d.get("prefixes", []):
                n = n.strip().rstrip("-_")
                if n and n not in names:
                    names.append(n)

        out.append({
            "key": m["model_key"],
            "name": names[0] if names else m["model_key"],
            "family": family,
            "variant": variant,
            "width": width,
            "dpi": p.get("dev_dpi", 203),
            "spp": bool(p.get("use_spp")),
            "mtu": p.get("ble_mtu_request"),
            "rle": (p.get("default_image_pipeline") or {}).get("encoding") == "tiny_rle",
            "postFeed": p.get("post_print_feed_count", 2),
            "speed": speed,
            "energy": levels(pd.get("energy")),
            "density": density,
            "apps": [origins.get(o, o) for o in m.get("origin_ids", [])],
            "origins": m.get("origin_ids", []),
            "whitespace": m.get("whitespace_mode", "remove"),
            "detect": detections,
        })

    json.dump({
        "source": "TiMini-Print printer catalog (Apache-2.0), converted by tools/build_printer_catalog.py",
        "models": out,
    }, open(out_path, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    print("kept %d models, skipped %d" % (len(out), len(skipped)))
    for k, v in sorted(skipped.items()):
        print("  skipped", k, "-", v)


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    main(sys.argv[1], os.path.join(here, "..", "app", "src", "main", "assets", "printers", "catalog.json"))
