package com.cloudimage.core.data.platform

import androidx.core.content.FileProvider

/**
 * A FileProvider with its own class name, purely so it can live as a
 * separate manifest node from the app's shared `fileprovider` (the
 * manifest merger keys providers by android:name and rejects two nodes
 * with the same class but different authorities).
 */
class UpdateFileProvider : FileProvider()
