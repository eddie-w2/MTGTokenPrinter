import sys
from timiniprint.devices.catalog import PrinterCatalog
from timiniprint.raster import RasterBuffer, RasterSet, PixelFormat
from timiniprint.protocol.job import PrinterProtocol
from timiniprint.protocol.families.v5x import build_sign_response
cat = PrinterCatalog.load()
names = [("X6",()),("X6 Printer",()),("MX06",()),("MX06",(bytes([1,2,3,4,0x59]),)),("GB01",()),("gb03-1234",()),("MXW01",()),("LX-D02",()),("AH-M2",()),("M02 Pro",()),("Mr.in_M02S",()),("T02",()),("YT01",()),("Phone",()),("M110",())]
for n,m in names:
    r = cat.detect_model(n, "AA:BB:CC:DD:EE:FF", manufacturer_data=m)
    print("detect '%s' mfr=%d:"%(n,len(m)), ", ".join(x.model.model_key for x in r))

def raster(w,h):
    px=[]
    for y in range(h):
        for x in range(w):
            black = ((x//7 + y//5) % 3 == 0) or (x < 20 and y < 10) or (y == h-1)
            px.append(1 if black else 0)
    return RasterBuffer(pixels=px, width=w, pixel_format=PixelFormat.BW1)

out = sys.argv[1]
ok = True
def check(name, data):
    global ok
    k = open(f"{out}/{name}.hex").read().strip()
    p = data.hex()
    same = k == p
    ok &= same
    if not same:
        # first difference
        i = next((i for i in range(min(len(k),len(p))) if k[i]!=p[i]), min(len(k),len(p)))
        print(name, "DIFF at hex", i, "kotlin", k[max(0,i-20):i+40], "\n   python", p[max(0,i-20):i+40], len(k), len(p))
    else:
        print(name, "same", len(k)//2, "bytes")

bl = {1:1, 2:3, 3:5}
for key in ["gb01","x6","mx05","dl_x2pro","seznikneo"]:
    dev = cat.device_from_model(key)
    for dark in (1,2,3):
        r = raster(dev.profile.paper_presets[0].paper_width_px if hasattr(dev.profile,'paper_presets') else 384, 30)
        job = PrinterProtocol(dev).build_job(RasterSet.from_single(r), is_text=False, blackening=bl[dark], feed_padding=12)
        check(f"{key}-{dark}", job.payload)
for key in ["phomemo_m02","phomemo_m02s"]:
    dev = cat.device_from_model(key)
    w = dev.profile.paper_presets[0].paper_width_px
    job = PrinterProtocol(dev).build_job(RasterSet.from_single(raster(w,30)), is_text=False, blackening=3)
    check(f"{key}-2", job.payload)
check("sign", build_sign_response(bytes((i*17+3)&0xFF for i in range(10)), timestamp_ms=1730290000123))
print("ALL SAME" if ok else "MISMATCH")
