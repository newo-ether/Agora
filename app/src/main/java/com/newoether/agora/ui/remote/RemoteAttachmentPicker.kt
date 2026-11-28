package com.newoether.agora.ui.remote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.ui.chat.bottombar.AttachmentAddMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteAttachmentPicker(owner: String, enabled: Boolean, itemsEnabled: Boolean, vm: RemoteViewModel) {
    var photoOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var fileOwner by rememberSaveable { mutableStateOf<String?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(16)) { uris ->
        photoOwner?.let { vm.addAttachments(it, uris) }; photoOwner = null
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileOwner?.let { vm.addAttachments(it, uris) }; fileOwner = null
    }
    AttachmentAddMenu(enabled = enabled, showCamera = false, showVideos = false, itemsEnabled = itemsEnabled,
        onCamera = {}, onVideos = {},
        onPhotos = { photoOwner = owner; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onFiles = { fileOwner = owner; files.launch(arrayOf("*/*")) })
}
