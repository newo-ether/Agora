package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.newoether.agora.R

@Composable
internal fun ModelProviderNameSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val rippleOutset = 8.dp
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(top = 8.dp)
            .layout { measurable, constraints ->
                val outset = rippleOutset.roundToPx()
                val placeable = measurable.measure(constraints.offset(horizontal = outset * 2))
                layout(placeable.width - outset * 2, placeable.height) {
                    placeable.placeRelative(-outset, 0)
                }
            }
            .clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = rippleOutset, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp, end = 12.dp)) {
            Text(
                stringResource(R.string.models_show_provider_name),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                stringResource(R.string.models_show_provider_name_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
