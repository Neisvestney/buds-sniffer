package io.github.neisvestney.budssniffer.buds

data class BudLevel(val percent: Int, val charging: Boolean)

data class BudsBattery(
    val left: BudLevel?,
    val right: BudLevel?,
    val case: BudLevel?,
    val updatedAt: Long,
) {
    // A component reported as "no data" (e.g. a bud inside a closed case) keeps its last known level.
    fun mergedOnto(previous: BudsBattery?) = copy(
        left = left ?: previous?.left,
        right = right ?: previous?.right,
        case = case ?: previous?.case,
    )
}

// A stale level can't claim it is still charging.
fun BudLevel?.format(live: Boolean = true): String = when {
    this == null -> "—"
    charging && live -> "$percent%⚡"
    else -> "$percent%"
}

fun BudsBattery.summary() = "L ${left.format()} · R ${right.format()} · case ${case.format()}"
