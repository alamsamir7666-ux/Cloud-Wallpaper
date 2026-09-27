package com.cloudimage.core.designsystem

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/**
 * The house switch for dense rows (v1.0.18): the material3 switch drawn
 * at 80% — the stock 52x32dp control reads oversized next to card titles
 * and settings rows, while the scaled ~42x26dp footprint matches the
 * compact toggles standard mobile settings use.
 *
 * Colors, thumb animation, and interaction behavior are exactly the
 * material3 defaults; only the drawn scale changes. Callers keep their
 * own modifiers (padding, test tags) as with a plain [Switch].
 */
@Composable
fun CompactSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.scale(CompactSwitchScale),
    )
}

private const val CompactSwitchScale = 0.8f
