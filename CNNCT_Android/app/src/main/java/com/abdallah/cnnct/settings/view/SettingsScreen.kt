// view/SettingsScreen.kt
package com.abdallah.cnnct.settings.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Lavender = Color(0xFFF1EAF5)   // background
private val RowGray  = Color(0xFFD1D5DB)   // row background
private val TextBlack = Color(0xFF111827)  // text/icons
private val FooterGray = Color(0xFF6B7280) // footer text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onBackClick: () -> Unit,
    onNavigate: (String) -> Unit
) {
    // Inner Scaffold handles the top app bar; outer Scaffold (in Activity) handles bottom bar
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Settings", color = TextBlack, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings Icon",
                            tint = TextBlack
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextBlack
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Lavender)
                // Respect both paddings: from Activity (bottom nav) and our top app bar
                .padding(contentPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val rows = listOf("Account", "Privacy", "Notifications", "Archived Chats", "Blocked Accounts")
            rows.forEachIndexed { idx, label ->
                SettingRow(
                    label = label,
                    onClick = { onNavigate(label) },
                    cornerRadius = 12.dp,
                    verticalPadding = 16.dp,
                    horizontalPadding = 20.dp
                )
                if (idx != rows.lastIndex) Spacer(Modifier.height(8.dp)) // margin between rows
            }

            Spacer(Modifier.weight(1f)) // push footer to bottom (above the app bottom bar)

            // Footer text (page-level), centered
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "CNNCT© 2026",
                    color = FooterGray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    onClick: () -> Unit,
    cornerRadius: Dp,
    verticalPadding: Dp,
    horizontalPadding: Dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RowGray, RoundedCornerShape(cornerRadius))
            .clickable(onClick = onClick) // ripple on press
            .padding(vertical = verticalPadding, horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = TextBlack,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium // medium weight text
        )
        Icon(
            imageVector = Icons.Default.ArrowForward,
            contentDescription = "Go to $label",
            tint = TextBlack
        )
    }
}
