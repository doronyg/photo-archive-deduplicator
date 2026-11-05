package com.doronyg.photoarchivededuplicator.strings

object PlatformStrings {
    const val mediaPermissionNeeded = "Photo and video access is needed to scan"
    const val scanPhotoLibrary = "Scan photo library"
    const val chooseScanFolder = "Choose a folder to scan"
    const val desktopOpenUnsupported = "Opening files and folders is not supported on this desktop."
    const val absoluteNativePath = "A native target must be an absolute path."
    const val mediaUriRequired = "A MediaStore content URI is required."
    const val mediaPermissionsError = "MediaStore did not return a cursor. Check photo and video permissions."
    const val mediaVolumeMissing = "MediaStore did not supply the media volume."
    const val mediaOnly = "Only images and videos can be opened from this inventory."

    fun notRegular(path: String) = "Not a regular file or folder: $path"
    fun inaccessible(path: String) = "File or folder no longer exists or is inaccessible: $path"
    fun notFolder(path: String) = "Not a folder: $path"
    fun notFile(path: String) = "Not a file: $path"
    fun mediaMissing(path: String) = "This photo or video is missing from MediaStore or is not accessible: $path"
    fun androidFolderUnsupported(path: String) = "Use your file manager to browse $path; opening folders is not supported here."
}
