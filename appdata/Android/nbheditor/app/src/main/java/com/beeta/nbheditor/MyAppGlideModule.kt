package com.beeta.nbheditor

import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule

/**
 * Glide requires an @GlideModule annotated class extending AppGlideModule to generate the
 * required Glide API (GlideApp) and to avoid the warning about a missing GeneratedAppGlideModule.
 * The implementation can be empty – we simply disable manifest parsing for faster startup.
 */
@GlideModule
class MyAppGlideModule : AppGlideModule() {
    // Disable manifest parsing to avoid adding similar modules twice.
    override fun isManifestParsingEnabled(): Boolean = false
}
