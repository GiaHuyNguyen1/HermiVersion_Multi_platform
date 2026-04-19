package com.hermitech.hermivision.ui.picker

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Design Tokens ──
private val DarkBg = Color(0xFF0F0F23)
private val AccentOrange = Color(0xFFFF6B35)
private val AccentTeal = Color(0xFF00BFA5)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF9E9E9E)
private val CardBg = Color(0xFF1A1A2E)

@Composable
fun VideoPickerScreen(onVideoSelected: (Uri) -> Unit, onLiveAnalysis: () -> Unit = {}) {
    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onVideoSelected(it) }
    }

    Scaffold(containerColor = DarkBg) 
    { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                // ── App title ──
                Text(
                    text = "Hermi-Vision",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ── Video analysis button ──
                Button(
                    onClick = { launcher.launch("video/*") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentOrange
                    )
                ) {
                    Text(
                        text = "Analyze Video",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // ── Live camera button ──
                OutlinedButton(
                    onClick = onLiveAnalysis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AccentTeal
                    )
                ) {
                    Text(
                        text = "Live video",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // ── Hint text ──
                Text(
                    text = "YOLO Ball Detection + MobileNet Court Detection",
                    color = TextSecondary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}