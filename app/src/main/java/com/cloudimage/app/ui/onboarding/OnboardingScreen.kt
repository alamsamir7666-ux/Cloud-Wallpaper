package com.cloudimage.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cloudimage.app.R

/**
 * First-run welcome: a one-screen introduction with the one decision that
 * matters up front — content safety — and a button into the app.
 *
 * The SFW choice defaults to ON; completing the flow persists it (and the
 * completed flag) through [com.cloudimage.app.ui.AppViewModel].
 */
@Composable
fun OnboardingScreen(
    onDone: (sfwOnly: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sfwOnly by remember { mutableStateOf(true) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 64.dp)
                .testTag("onboarding:root"),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                imageVector = Icons.Rounded.Wallpaper,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(24.dp).size(48.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))
        FeatureRow(
            icon = Icons.Rounded.Wallpaper,
            titleRes = R.string.onboarding_feature_browse_title,
            bodyRes = R.string.onboarding_feature_browse_body,
        )
        FeatureRow(
            icon = Icons.Rounded.Download,
            titleRes = R.string.onboarding_feature_apply_title,
            bodyRes = R.string.onboarding_feature_apply_body,
        )
        FeatureRow(
            icon = Icons.Rounded.Extension,
            titleRes = R.string.onboarding_feature_extensions_title,
            bodyRes = R.string.onboarding_feature_extensions_body,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.HealthAndSafety,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_sfw_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_sfw_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = sfwOnly, onCheckedChange = { sfwOnly = it })
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { onDone(sfwOnly) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(R.string.onboarding_get_started))
        }
        Text(
            text = stringResource(R.string.onboarding_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    titleRes: Int,
    bodyRes: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Column {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
