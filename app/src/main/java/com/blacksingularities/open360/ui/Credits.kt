package com.blacksingularities.open360.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val AUTHOR_URL = "https://redzikowski.com"
const val COFFEE_URL = "https://buycoffee.to/adamredzikowski"

/** Jednolita stopka autorska – ta sama treść w aplikacji i na stronie. */
@Composable
fun Credits(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Pomysł i wykonanie: Adam Rędzikowski · redzikowski.com",
            color = Palette.TextDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { runCatching { uri.openUri(AUTHOR_URL) } }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .pressable("Postaw kawę na buycoffee.to") { runCatching { uri.openUri(COFFEE_URL) } }
                .heightIn(min = 46.dp)
                .clip(RoundedCornerShape(50))
                .background(Palette.Coffee)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("☕", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text("Postaw kawę na buycoffee.to", color = Palette.OnCoffee, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
