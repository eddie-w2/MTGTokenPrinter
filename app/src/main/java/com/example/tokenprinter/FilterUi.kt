@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.tokenprinter

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Top-bar button with the sort choices: alphabetical and release date. */
@Composable
fun SortMenuButton(vm: MainViewModel) {
    val f by vm.filters.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.SortByAlpha, contentDescription = "Sort")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortMode.values().forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    onClick = {
                        vm.setSort(m)
                        open = false
                    },
                    leadingIcon = { if (f.sort == m) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                )
            }
        }
    }
}

/** Opens the type / set sheet. A dot shows when either is active. */
@Composable
fun FilterButton(vm: MainViewModel, onClick: () -> Unit) {
    val f by vm.filters.collectAsStateWithLifecycle()
    val active = f.types.isNotEmpty() || f.setCode != null
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (active) Badge() }) {
            Icon(Icons.Filled.FilterList, contentDescription = "Filters")
        }
    }
}

@Composable
fun FilterSheet(vm: MainViewModel, onClose: () -> Unit) {
    val f by vm.filters.collectAsStateWithLifecycle()
    val sets by vm.sets.collectAsStateWithLifecycle()
    var setMenu by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onClose, sheetState = state) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)

            Text("Type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TYPE_CATS.forEach { c ->
                    FilterChip(selected = c in f.types, onClick = { vm.toggleType(c) }, label = { Text(c) })
                }
            }

            Text("Set", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Box {
                OutlinedButton(onClick = { setMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        sets.firstOrNull { it.code == f.setCode }?.let { "${it.name} (${it.code.uppercase()})" } ?: "All sets",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = setMenu,
                    onDismissRequest = { setMenu = false },
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text("All sets") },
                        onClick = {
                            vm.setSet(null)
                            setMenu = false
                        },
                    )
                    sets.forEach { s ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${s.code.uppercase()} · ${s.released}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                vm.setSet(s.code)
                                setMenu = false
                            },
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.clearFilters() }) { Text("Clear filters") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose) { Text("Done") }
            }
        }
    }
}
