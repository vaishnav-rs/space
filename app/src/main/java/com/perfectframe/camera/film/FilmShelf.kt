package com.perfectframe.camera.film

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perfectframe.camera.ui.theme.Surface0
import com.perfectframe.camera.ui.theme.TextPrimary
import com.perfectframe.camera.ui.theme.TextSecondary
import com.perfectframe.camera.ui.theme.TextTertiary

/**
 * "Loading film": a horizontally-scrolling stack of stock boxes. Tapping one loads it — a brief
 * scale-up flourish stands in for pulling a canister off the shelf before the sheet dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilmShelf(onLoad: (FilmStock) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picked by remember { mutableStateOf<FilmStock?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surface0,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Text(
                text = "Load film",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
            )
            Text(
                text = "Pick a roll — one shot in, straight to the darkroom",
                color = TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp, bottom = 14.dp),
            )
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(FilmStocks.ALL, key = { it.id }) { stock ->
                    FilmBoxCard(
                        stock = stock,
                        selected = stock == picked,
                        onClick = {
                            picked = stock
                            onLoad(stock)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilmBoxCard(stock: FilmStock, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.08f else 1f, label = "boxScale")
    Column(
        modifier = Modifier
            .width(96.dp)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(stock.boxColor)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x22000000)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stock.displayName,
            color = stock.accentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "ISO ${stock.iso}",
            color = stock.accentColor.copy(alpha = 0.75f),
            fontSize = 10.sp,
        )
        Text(
            text = stock.tagline,
            color = stock.accentColor.copy(alpha = 0.75f),
            fontSize = 9.sp,
            maxLines = 2,
        )
    }
}
