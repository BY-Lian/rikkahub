package me.rerere.rikkahub.ui.components.ai

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.ui.sketch.SketchDialog
import me.rerere.ui.sketch.SketchResult
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

internal data class ChatAttachmentPickerActions(
    val onTakePicture: () -> Unit,
    val onPickImage: () -> Unit,
    val onPickVideo: () -> Unit,
    val onPickAudio: () -> Unit,
    val onPickFile: () -> Unit,
    val onSketch: () -> Unit,
)

// ======================= 兼容性探测与兜底（新增） =======================

internal enum class PickRoute { CONTENT, DOCUMENT, IN_APP, NONE }

/** 按顺序探测系统里到底有没有 App 能接这个 Intent */
internal fun resolvePickRoute(context: Context, mime: String): PickRoute {
    val pm = context.packageManager
    fun can(i: Intent) = i.resolveActivity(pm) != null

    val content = Intent(Intent.ACTION_GET_CONTENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(mime)
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    if (can(content)) return PickRoute.CONTENT

    val document = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(mime)
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    if (can(document)) return PickRoute.DOCUMENT

    if (mime.startsWith("image/")) return PickRoute.IN_APP
    return PickRoute.NONE
}

private fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasMediaPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, mediaPermission()) == PackageManager.PERMISSION_GRANTED

private data class LocalImage(val uri: Uri, val name: String)

private suspend fun loadLocalImages(context: Context, limit: Int = 300): List<LocalImage> =
    withContext(Dispatchers.IO) {
        val out = ArrayList<LocalImage>()
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                cols,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext() && out.size < limit) {
                    out += LocalImage(
                        uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idCol),
                        ),
                        name = cursor.getString(nameCol) ?: "image",
                    )
                }
            }
        }
        out
    }

@Composable
private fun ImageThumb(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(input, null, opts)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** 内置相册：完全绕开系统选择器 */
@Composable
private fun InAppImagePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    var images by remember { mutableStateOf<List<LocalImage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val selected = remember { mutableStateListOf<Uri>() }

    LaunchedEffect(Unit) {
        images = loadLocalImages(context)
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择图片") },
        text = {
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                images.isEmpty() -> Text("读不到系统相册（当前运行环境未向应用开放相册）。可以改用「复制图片 → 在输入框长按粘贴」。")

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                ) {
                    items(images, key = { it.uri.toString() }) { img ->
                        val isSelected = img.uri in selected
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .clickable {
                                    if (isSelected) selected.remove(img.uri) else selected.add(img.uri)
                                },
                        ) {
                            ImageThumb(img.uri, Modifier.fillMaxSize())
                            if (isSelected) {
                                Text("✓", style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) { Text("确定(${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ======================= 原有逻辑（仅把 launch 换成安全启动） =======================

@Composable
internal fun rememberChatAttachmentPickerActions(
    inputState: ChatInputState,
    setting: Settings,
    onAttachmentAdded: () -> Unit,
): ChatAttachmentPickerActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val launchCameraCrop = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            onAttachmentAdded()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                onAttachmentAdded()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onTakePicture: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cameraOutputFile!!
            )
            try {
                cameraLauncher.launch(cameraOutputUri!!)
            } catch (e: ActivityNotFoundException) {
                toaster.show("当前环境不支持调用相机", type = ToastType.Error)
            }
        } else {
            cameraPermission.requestPermissions()
        }
    }

    val launchImageCrop = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            onAttachmentAdded()
        },
    )

    // 把「拿到 uri 之后干什么」抽出来，系统选择器和内置选择器共用
    val handleImages: (List<Uri>) -> Unit = { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            Log.d("ImagePickButton", "Selected URIs: $selectedUris")
            if (!setting.displaySetting.skipCropImage && selectedUris.size == 1) {
                launchImageCrop(selectedUris.first())
            } else {
                inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                onAttachmentAdded()
            }
        } else {
            Log.d("ImagePickButton", "No images selected")
        }
    }

    val handleVideos: (List<Uri>) -> Unit = { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
            onAttachmentAdded()
        }
    }

    val handleAudios: (List<Uri>) -> Unit = { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
            onAttachmentAdded()
        }
    }

    val handleDocuments: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            val documents = uris.mapNotNull { uri ->
                val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                if (isAllowedFileType(fileName, mime)) {
                    val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                        ?: run {
                            toaster.show(
                                resources.getString(R.string.chat_input_file_read_failed, fileName),
                                type = ToastType.Error
                            )
                            return@mapNotNull null
                        }
                    UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                } else {
                    toaster.show(
                        resources.getString(R.string.chat_input_unsupported_file_type, fileName),
                        type = ToastType.Error
                    )
                    null
                }
            }
            if (documents.isNotEmpty()) {
                inputState.addFiles(documents)
                onAttachmentAdded()
            }
        }
    }

    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            handleImages(selectedUris)
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            handleVideos(selectedUris)
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            handleAudios(selectedUris)
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            handleDocuments(uris)
        }

    // 兜底：用 SAF 文档选择器接管任意 mime
    var pendingMime by remember { mutableStateOf<String?>(null) }
    val fallbackDocLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val mime = pendingMime
            pendingMime = null
            when {
                mime == null -> handleDocuments(uris)
                mime.startsWith("image/") -> handleImages(uris)
                mime.startsWith("video/") -> handleVideos(uris)
                mime.startsWith("audio/") -> handleAudios(uris)
                else -> handleDocuments(uris)
            }
        }

    var showInAppPicker by remember { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun tryLaunch(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("ImagePickButton", "no activity for intent", e)
        false
    } catch (e: SecurityException) {
        Log.w("ImagePickButton", "security exception", e)
        false
    } catch (e: Exception) {
        Log.w("ImagePickButton", "launch failed", e)
        false
    }

    val pick: (String) -> Unit = pick@{ mime ->
        val route = resolvePickRoute(context, mime)
        Log.d("ImagePickButton", "pick mime=$mime route=$route")

        var launched = false
        when (route) {
            PickRoute.CONTENT -> launched = tryLaunch {
                when {
                    mime.startsWith("image/") -> imagePickerLauncher.launch(mime)
                    mime.startsWith("video/") -> videoPickerLauncher.launch(mime)
                    mime.startsWith("audio/") -> audioPickerLauncher.launch(mime)
                    else -> filePickerLauncher.launch(arrayOf(mime))
                }
            }

            PickRoute.DOCUMENT -> {
                pendingMime = mime
                launched = tryLaunch { fallbackDocLauncher.launch(arrayOf(mime)) }
                if (!launched) pendingMime = null
            }

            PickRoute.IN_APP -> {
                if (!hasMediaPermission(context)) permissionLauncher.launch(mediaPermission())
                showInAppPicker = true
                launched = true
            }

            PickRoute.NONE -> launched = false
        }

        // 图片还有最后一条路：内置相册
        if (!launched && mime.startsWith("image/")) {
            if (!hasMediaPermission(context)) permissionLauncher.launch(mediaPermission())
            showInAppPicker = true
            launched = true
        }

        if (!launched) {
            toaster.show("当前运行环境不支持打开文件选择器", type = ToastType.Error)
        }
    }

    if (showInAppPicker) {
        InAppImagePickerDialog(
            onDismiss = { showInAppPicker = false },
            onConfirm = { uris ->
                showInAppPicker = false
                if (uris.isNotEmpty()) handleImages(uris)
            },
        )
    }

    val scope = rememberCoroutineScope()
    var showSketch by remember { mutableStateOf(false) }
    if (showSketch) {
        SketchDialog(
            onDismiss = { showSketch = false },
            onConfirm = { result ->
                showSketch = false
                scope.launch {
                    inputState.addImages(listOfNotNull(filesManager.createChatFileBySketch(result)))
                    onAttachmentAdded()
                }
            },
        )
    }

    return ChatAttachmentPickerActions(
        onTakePicture = onTakePicture,
        onPickImage = { pick("image/*") },
        onPickVideo = { pick("video/*") },
        onPickAudio = { pick("audio/*") },
        onPickFile = { pick("*/*") },
        onSketch = { showSketch = true },
    )
}

/** 把画板的结果存成聊天附件。 */
internal suspend fun FilesManager.createChatFileBySketch(result: SketchResult): Uri? = withContext(Dispatchers.IO) {
    createChatFilesByByteArrays(
        byteArrays = listOf(result.encode()),
        displayName = "sketch.${result.extension}",
        mimeType = result.mimeType,
    ).firstOrNull()
}
