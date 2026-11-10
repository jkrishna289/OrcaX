package com.github.jkrishna289.orcax.ui.nav

import android.content.Context
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.ui.toServerString
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/**
 * A navigable top-level item (a server library, or More).
 *
 * Historically these fed the retired left nav drawer (hence the name); today they are the model
 * behind the Categories page, the engine home's local bundle builder, and library pinning in
 * settings. Libraries are created dynamically from the server.
 */
sealed interface NavDrawerItem {
    val id: String

    fun name(context: Context): String

    object More : NavDrawerItem {
        override val id: String
            get() = "a_more"

        override fun name(context: Context): String = context.getString(R.string.more)
    }

}

/**
 * A server provided nav item, typically a library
 */
data class ServerNavDrawerItem(
    val itemId: UUID,
    val name: String,
    val destination: Destination,
    val type: CollectionType,
) : NavDrawerItem {
    override val id: String = getId(itemId)

    override fun name(context: Context): String = name

    companion object {
        fun getId(itemId: UUID) = "s_" + itemId.toServerString()
    }
}
