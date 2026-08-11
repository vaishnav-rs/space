package com.perfectframe.camera.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.perfectframe.camera.editor.EditorScreen
import com.perfectframe.camera.ui.theme.Accent
import com.perfectframe.camera.ui.theme.Surface0
import com.perfectframe.camera.ui.theme.TextPrimary
import com.perfectframe.camera.ui.theme.TextSecondary
import com.perfectframe.camera.ui.theme.TextTertiary

/**
 * Full-screen in-app gallery of the photos the app captured. A 3-column grid opens into a
 * swipeable full-screen viewer with a share action. Reads only the app's own MediaStore
 * contributions, so on Q+ it needs no runtime permission (pre-Q asks for READ_EXTERNAL_STORAGE).
 */
@Composable
fun GalleryScreen(onClose: () -> Unit) {
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    var images by remember { mutableStateOf<List<GalleryImage>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    LaunchedEffect(granted, refreshKey) {
        if (granted) {
            images = loadPerfectFrameImages(context)
            loaded = true
        }
    }

    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Uri?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface0),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            GalleryTopBar(count = images.size, onClose = onClose)

            when {
                !loaded -> CenterMessage("Loading your shots…")
                images.isEmpty() -> CenterMessage("No photos yet.\nTap the shutter to capture your first frame.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                ) {
                    items(images, key = { it.uri.toString() }) { image ->
                        val index = images.indexOf(image)
                        AsyncImage(
                            model = image.uri,
                            contentDescription = "Captured photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(2.dp)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF15171B))
                                .clickable { viewerIndex = index },
                        )
                    }
                }
            }
        }

        viewerIndex?.let { start ->
            PhotoViewer(
                images = images,
                startIndex = start,
                onClose = { viewerIndex = null },
                onShare = { uri -> shareImage(context, uri) },
                onEdit = { uri -> editing = uri },
            )
        }

        editing?.let { uri ->
            EditorScreen(
                imageUri = uri,
                onClose = { editing = null },
                onSaved = {
                    editing = null
                    viewerIndex = null
                    refreshKey++
                },
            )
        }
    }
}

@Composable
private fun GalleryTopBar(count: Int, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircleIconButton(Icons.Rounded.ArrowBack, "Back", onClose)
        Icon(Icons.Rounded.PhotoLibrary, null, tint = Accent, modifier = Modifier.size(20.dp))
        Text("Gallery", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (count > 0) {
            Text("$count", color = TextTertiary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PhotoViewer(
    images: List<GalleryImage>,
    startIndex: Int,
    onClose: () -> Unit,
    onShare: (Uri) -> Unit,
    onEdit: (Uri) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { images.size })
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            AsyncImage(
                model = images[page].uri,
                contentDescription = "Photo ${page + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(Icons.Rounded.ArrowBack, "Back", onClose)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircleIconButton(Icons.Rounded.Edit, "Edit") {
                    images.getOrNull(pagerState.currentPage)?.let { onEdit(it.uri) }
                }
                CircleIconButton(Icons.Rounded.Share, "Share") {
                    images.getOrNull(pagerState.currentPage)?.let { onShare(it.uri) }
                }
            }
        }
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = TextPrimary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 15.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

private fun shareImage(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share photo"))
}
