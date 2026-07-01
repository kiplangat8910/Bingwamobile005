package com.bingwa.mobile

import android.content.Context
import android.telephony.SubscriptionInfo

internal const val USSD_SIM_SELECTION_SLOT_1 = -1
internal const val USSD_SIM_SELECTION_SLOT_2 = -2
internal const val USSD_SIM_SELECTION_BOTH = -3

internal data class UssdSimTarget(
    val subId: Int,
    val slotIndex: Int
)

internal fun normalizeUssdSimSelection(rawSelection: Int, sims: List<SubscriptionInfo>): Int {
    return when (rawSelection) {
        USSD_SIM_SELECTION_SLOT_1,
        USSD_SIM_SELECTION_SLOT_2,
        USSD_SIM_SELECTION_BOTH -> rawSelection
        else -> {
            val matched = sims.firstOrNull { it.subscriptionId == rawSelection }
            when (matched?.simSlotIndex) {
                1 -> USSD_SIM_SELECTION_SLOT_2
                else -> USSD_SIM_SELECTION_SLOT_1
            }
        }
    }
}

internal fun currentUssdSimSelection(context: Context): Int {
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val sims = getAvailableSims(context)
    return normalizeUssdSimSelection(
        rawSelection = prefs.safeGetInt("selected_sim_id", USSD_SIM_SELECTION_SLOT_1),
        sims = sims
    )
}

internal fun resolvePreferredUssdSubId(context: Context): Int? =
    resolveUssdSimTargets(context).firstOrNull()?.subId

internal fun resolveUssdSimTargets(context: Context): List<UssdSimTarget> {
    val sims = getAvailableSims(context).sortedBy { it.simSlotIndex }
    if (sims.isEmpty()) return emptyList()

    val slot1 = sims.firstOrNull { it.simSlotIndex == 0 } ?: sims.getOrNull(0)
    val slot2 = sims.firstOrNull { it.simSlotIndex == 1 } ?: sims.getOrNull(1)
    val selection = normalizeUssdSimSelection(
        rawSelection = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .safeGetInt("selected_sim_id", USSD_SIM_SELECTION_SLOT_1),
        sims = sims
    )

    return when (selection) {
        USSD_SIM_SELECTION_SLOT_2 -> listOfNotNull(slot2 ?: slot1)
        USSD_SIM_SELECTION_BOTH -> listOfNotNull(slot1, slot2)
        else -> listOfNotNull(slot1 ?: slot2)
    }.distinctBy { it.subscriptionId }
        .map { UssdSimTarget(subId = it.subscriptionId, slotIndex = it.simSlotIndex) }
}

internal fun describeUssdSimSelection(selection: Int, sims: List<SubscriptionInfo>): String {
    val slot1 = sims.firstOrNull { it.simSlotIndex == 0 } ?: sims.getOrNull(0)
    val slot2 = sims.firstOrNull { it.simSlotIndex == 1 } ?: sims.getOrNull(1)

    return when (normalizeUssdSimSelection(selection, sims)) {
        USSD_SIM_SELECTION_SLOT_2 -> formatUssdSlotLabel(2, slot2)
        USSD_SIM_SELECTION_BOTH -> {
            val names = listOfNotNull(
                slot1?.displayName?.toString()?.takeIf { it.isNotBlank() }?.let { "SIM 1: $it" },
                slot2?.displayName?.toString()?.takeIf { it.isNotBlank() }?.let { "SIM 2: $it" }
            )
            if (names.isEmpty()) "Both Slots" else "Both Slots (${names.joinToString(" | ")})"
        }
        else -> formatUssdSlotLabel(1, slot1)
    }
}

private fun formatUssdSlotLabel(slotNumber: Int, simInfo: SubscriptionInfo?): String {
    val name = simInfo?.displayName?.toString()?.trim().orEmpty()
    return if (name.isBlank()) {
        "Slot $slotNumber"
    } else {
        "Slot $slotNumber · $name"
    }
}
