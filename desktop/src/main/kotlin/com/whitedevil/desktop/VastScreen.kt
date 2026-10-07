package com.whitedevil.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whitedevil.desktop.ops.ActionController
import com.whitedevil.desktop.ops.ActionDialog
import com.whitedevil.desktop.ops.ActionSpec
import com.whitedevil.desktop.ops.EmptyLine
import com.whitedevil.desktop.ops.KeyValue
import com.whitedevil.desktop.ops.LoadOnce
import com.whitedevil.desktop.ops.Note
import com.whitedevil.desktop.ops.OfferQuery
import com.whitedevil.desktop.ops.OpsScreenFrame
import com.whitedevil.desktop.ops.OpsState
import com.whitedevil.desktop.ops.PanelState
import com.whitedevil.desktop.ops.PanelView
import com.whitedevil.desktop.ops.Picker
import com.whitedevil.desktop.ops.Pill
import com.whitedevil.desktop.ops.SectionCard
import com.whitedevil.desktop.ops.Tile
import com.whitedevil.desktop.ops.TileRow
import com.whitedevil.desktop.ops.Tone
import com.whitedevil.desktop.ops.VastActions
import com.whitedevil.desktop.ops.VastApi
import com.whitedevil.desktop.ops.VastInstance
import com.whitedevil.desktop.ops.VastOffer
import com.whitedevil.desktop.ops.VastOffers
import com.whitedevil.desktop.ops.VastState
import com.whitedevil.desktop.ops.ageOfEpochSec
import com.whitedevil.desktop.ops.formatAge
import com.whitedevil.desktop.ops.formatClock
import com.whitedevil.desktop.ops.money
import com.whitedevil.desktop.ops.plainNumber
import com.whitedevil.desktop.ops.rememberOpsClients
import kotlinx.coroutines.launch

/**
 * Vast.ai: account credit and instances (GET /state), and an offer search (GET /offers, only when
 * you press Search: it asks Vast, so nothing is fetched in the background).
 *
 * Renting, starting, stopping and deleting each open a confirmation first. Rent and Start bill and
 * need a typed word; Delete destroys the disk and needs a typed word; Stop releases the GPU and is
 * a two-step. The hub's answer is shown as it came: a rental counts only if it returns an instance
 * id, and a start/stop/delete that answers HTTP 200 with success:false is shown as failed.
 * Nothing polls on this screen.
 */
@Composable
fun VastScreen(settings: Settings, onCreate: (String) -> Unit = {}) {
    val clients = rememberOpsClients(settings)
    val api = remember(clients) { VastApi(clients.reader) }
    val actions = remember(clients) { VastActions(clients.actor) }
    val scope = rememberCoroutineScope()
    val state = remember(api) { PanelState(api::state) }
    var offers by remember(api) { mutableStateOf<PanelState<VastOffers>?>(null) }
    // After a confirmed action ends, re-read the instance list (a GET) to show what Vast now says.
    val controller = remember(state) { ActionController(onFinished = { scope.launch { state.refresh(followUp = true) } }) }

    LoadOnce(state)

    OpsScreenFrame(
        title = "Vast",
        subtitle = state.lastGood?.let { "Updated ${formatClock(it.atMillis)} · reads on Refresh, nothing polls" } ?: "Reads on Refresh, nothing polls",
        refreshing = state.refreshing,
        onRefresh = { scope.launch { state.refresh() } },
    ) {
        RenderHereCard(
            "Vast",
            listOf("Rent and manage machines here. Renders are built in Create and go to whichever runner is connected to the hub: the hub keeps one 14B runner connection and one LTX connection."),
            onCreate,
        )
        Note(
            "A running instance bills by the hour. Stopping releases the GPU but the disk keeps billing until you delete the instance. " +
                "Rent, Start, Stop and Delete each ask for confirmation first and then show the hub's answer.",
        )
        PanelView(state, "Vast account and instances", onRetry = { scope.launch { state.refresh() } }) { s, stale ->
            AccountCard(s)
            InstancesCard(s, stale, controller, actions)
        }
        val credit = (state.state as? OpsState.Loaded)?.value?.credit
        OfferSearchCard(
            onSearch = { query ->
                val panel = PanelState(load = { api.offers(query) })
                offers = panel
                scope.launch { panel.refresh() }
            },
            searching = offers?.refreshing == true,
        )
        offers?.let { panel ->
            PanelView(panel, "Vast offers", onRetry = { scope.launch { panel.refresh() } }) { found, stale ->
                OffersCard(found, stale, credit, panel.lastGood?.atMillis, controller, actions)
            }
        }
    }
    ActionDialog(controller)
}

private fun statusTone(i: VastInstance): Tone = when {
    i.running -> Tone.Ok
    i.stopped -> Tone.Neutral
    (i.status ?: "").contains("error", ignoreCase = true) -> Tone.Bad
    else -> Tone.Warn
}

@Composable
private fun AccountCard(s: VastState) {
    val running = s.instances.filter { it.running }
    val known = running.mapNotNull { it.pricePerHour }
    SectionCard("Account") {
        TileRow {
            Tile("Credit", money(s.credit), null, Modifier.weight(1f))
            Tile("Instances", "${s.instances.size}", "${running.size} running", Modifier.weight(1f))
            Tile(
                "Running spend",
                if (running.isEmpty()) "none" else money(known.sum(), 3) + "/h",
                if (known.size < running.size) "some prices unknown" else "sum of running instances",
                Modifier.weight(1f),
                tone = if (running.isNotEmpty()) Tone.Warn else Tone.Neutral,
            )
        }
        if (s.credit == 0.0) {
            EmptyLine("A credit of 0.00 can also mean Vast did not report one (the hub reports 0 in that case). Check console.vast.ai if it matters.")
        }
        s.image?.let { KeyValue("Default image", it, mono = true) }
    }
}

@Composable
private fun InstancesCard(s: VastState, stale: Boolean, controller: ActionController, actions: VastActions) {
    SectionCard("Instances", trailing = { Text("${s.instances.size}", style = MaterialTheme.typography.labelMedium) }) {
        if (s.instances.isEmpty()) EmptyLine("Vast lists no instances on this account.")
        s.instances.forEach { InstanceRow(it, stale, controller, actions) }
    }
}

@Composable
private fun InstanceRow(i: VastInstance, stale: Boolean, controller: ActionController, actions: VastActions) {
    val label = i.label ?: "#${i.id}"
    val gpu = "${i.numGpus ?: 1}× ${i.gpu ?: "GPU unknown"}"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label  ·  $gpu", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(10.dp))
            Pill(i.status ?: "status unknown", statusTone(i))
        }
        Text(
            listOfNotNull(
                i.vramGb?.let { "$it GB VRAM" },
                i.pricePerHour?.let { "${money(it, 3)}/h running" },
                i.stoppedPricePerHour?.let { "${money(it, 3)}/h disk-only when stopped" },
                i.diskGb?.let { "$it GB disk" },
                i.location,
                "id ${i.id}",
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        i.statusMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        i.sshCommand?.let { KeyValue("SSH", it, mono = true) }
        ageOfEpochSec(i.startedEpochSec)?.let { KeyValue("Up since", "${formatAge(it)} ago") }
        val util = listOfNotNull(i.gpuUtil?.let { "GPU ${plainNumber(it, 0)}%" }, i.diskUsedGb?.let { "disk used ${plainNumber(it, 1)} GB" })
        if (util.isNotEmpty()) KeyValue("Usage", util.joinToString(" · "))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !stale && !controller.busy && !i.running, onClick = {
                controller.request(
                    ActionSpec(
                        title = "Start Vast instance $label",
                        consequences = listOfNotNull(
                            "Asks Vast to start instance ${i.id} ($gpu). It only starts if the host still has a free GPU.",
                            i.pricePerHour?.let { "It bills about ${money(it, 3)}/h while it runs." } ?: "It bills hourly while it runs; the hub has no price for it.",
                            "It keeps billing until you stop or delete it.",
                        ),
                        confirmLabel = "Start instance",
                        typedPhrase = "START",
                        run = { actions.start(i.id) },
                    ),
                )
            }) { Text("Start…") }
            OutlinedButton(enabled = !stale && !controller.busy && !i.stopped, onClick = {
                controller.request(
                    ActionSpec(
                        title = "Stop Vast instance $label",
                        consequences = listOfNotNull(
                            "Asks Vast to stop instance ${i.id}. Anything running on it stops.",
                            "The GPU is released, so the GPU charge ends.",
                            "The disk${i.diskGb?.let { " ($it GB)" } ?: ""} keeps billing${i.stoppedPricePerHour?.let { " at about ${money(it, 3)}/h" } ?: ""} until you delete the instance.",
                            "It can only be started again if the host has a free GPU.",
                        ),
                        confirmLabel = "Stop instance",
                        danger = false,
                        run = { actions.stop(i.id) },
                    ),
                )
            }) { Text("Stop…") }
            Button(
                enabled = !stale && !controller.busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                onClick = {
                    controller.request(
                        ActionSpec(
                            title = "Delete Vast instance $label",
                            consequences = listOf(
                                "Destroys instance ${i.id} and its disk permanently. Everything on it is lost.",
                                "All charges for it end.",
                            ),
                            confirmLabel = "Delete instance",
                            typedPhrase = "DELETE",
                            run = { actions.delete(i.id) },
                        ),
                    )
                },
            ) { Text("Delete…") }
        }
    }
}

@Composable
private fun OfferSearchCard(onSearch: (OfferQuery) -> Unit, searching: Boolean) {
    var minVram by remember { mutableStateOf("40") }
    var gpu by remember { mutableStateOf("") }
    var maxPrice by remember { mutableStateOf("") }
    var disk by remember { mutableStateOf("150") }
    var numGpus by remember { mutableStateOf("1") }

    val vramValue = minVram.trim().toDoubleOrNull()
    val priceValue = maxPrice.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val priceOk = maxPrice.isBlank() || priceValue != null
    val diskValue = disk.trim().toIntOrNull()
    val valid = vramValue != null && priceOk && diskValue != null && diskValue > 0

    SectionCard("Find a machine") {
        Text(
            "Searching asks Vast for verified, on-demand machines. It rents nothing.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(minVram, { minVram = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, label = { Text("Min VRAM (GB)") }, singleLine = true, modifier = Modifier.width(150.dp), isError = vramValue == null)
            OutlinedTextField(gpu, { gpu = it.take(40) }, label = { Text("GPU name (optional)") }, singleLine = true, modifier = Modifier.width(210.dp))
            OutlinedTextField(maxPrice, { maxPrice = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, label = { Text("Max $/h (optional)") }, singleLine = true, modifier = Modifier.width(210.dp), isError = !priceOk)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(disk, { disk = it.filter(Char::isDigit).take(5) }, label = { Text("Disk (GB)") }, singleLine = true, modifier = Modifier.width(150.dp), isError = diskValue == null || diskValue <= 0)
            Picker("GPUs", listOf("1", "2", "4", "8"), numGpus, { numGpus = it })
            Button(
                enabled = valid && !searching,
                onClick = {
                    onSearch(OfferQuery(vramValue!!, gpu.trim().ifEmpty { null }, priceValue, diskValue!!, numGpus.toInt()))
                },
            ) { Text(if (searching) "Searching…" else "Search offers") }
        }
    }
}

@Composable
private fun OffersCard(
    found: VastOffers,
    stale: Boolean,
    credit: Double?,
    searchedAtMillis: Long?,
    controller: ActionController,
    actions: VastActions,
) {
    val q = found.query
    SectionCard(
        "Offers",
        trailing = { Text(searchedAtMillis?.let { "searched ${formatClock(it)}" } ?: "", style = MaterialTheme.typography.labelSmall) },
    ) {
        Text(
            "Prices are per hour for a ${q.diskGb} GB disk, as of the search. Renting uses that same disk size.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (found.offers.isEmpty()) EmptyLine("The search returned no offers. Loosen the filters.")
        found.offers.forEach { o -> OfferRow(o, q.diskGb, stale, credit, controller, actions) }
    }
}

@Composable
private fun OfferRow(
    o: VastOffer,
    diskGb: Int,
    stale: Boolean,
    credit: Double?,
    controller: ActionController,
    actions: VastActions,
) {
    val gpu = "${o.numGpus ?: 1}× ${o.gpu ?: "GPU unknown"}"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$gpu  ·  ${o.pricePerHour?.let { money(it, 3) + "/h" } ?: "price unknown"}", style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(
                    o.vramGb?.let { "$it GB VRAM" },
                    o.location,
                    o.reliabilityPct?.let { "reliability ${plainNumber(it, 1)}%" },
                    o.downMbps?.let { "$it Mb/s down" },
                    o.cpuCores?.let { "$it cores" },
                    o.ramGb?.let { "$it GB RAM" },
                    o.cuda?.let { "CUDA $it" },
                    o.stoppedPricePerHour?.let { "${money(it, 3)}/h disk-only when stopped" },
                    "offer ${o.id}",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(enabled = !stale && !controller.busy && o.rentable != false, onClick = {
            controller.request(
                ActionSpec(
                    title = "Rent Vast offer ${o.id}",
                    consequences = listOfNotNull(
                        "Rents $gpu${o.vramGb?.let { " ($it GB VRAM)" } ?: ""}${o.location?.let { " in $it" } ?: ""} (offer ${o.id}) with a $diskGb GB disk.",
                        o.pricePerHour?.let { "It bills ${money(it, 3)} per hour, disk included, starting immediately and running until you stop or delete it." }
                            ?: "It bills hourly starting immediately, until you stop or delete it. The offer has no price in the reply, so the rate is unknown.",
                        "Stopping releases the GPU, but the disk keeps billing${o.stoppedPricePerHour?.let { " at about ${money(it, 3)}/h" } ?: ""}. Only deleting ends every charge.",
                        "The hub installs its SSH key on the machine as part of renting.",
                        credit?.let { "Account credit right now: ${money(it)}." },
                    ),
                    confirmLabel = "Rent machine",
                    typedPhrase = "RENT",
                    run = { actions.rent(o.id, diskGb, null) },
                ),
            )
        }) { Text(if (o.rentable == false) "Not rentable" else "Rent…") }
    }
}
