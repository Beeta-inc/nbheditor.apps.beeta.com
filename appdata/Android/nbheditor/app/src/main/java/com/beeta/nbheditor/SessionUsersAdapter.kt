package com.beeta.nbheditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SessionUsersAdapter(
    private var users: List<SessionUser>,
    private val currentUserId: String,
    private val isCreator: Boolean,
    private val onKickUser: (SessionUser) -> Unit
) : RecyclerView.Adapter<SessionUsersAdapter.UserViewHolder>() {

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivUserStatus: ImageView = view.findViewById(R.id.ivUserStatus)
        val tvUserName: TextView = view.findViewById(R.id.tvUserName)
        val tvUserEmail: TextView = view.findViewById(R.id.tvUserEmail)
        val tvCreatorBadge: TextView = view.findViewById(R.id.tvCreatorBadge)
        val tvTypingIndicator: TextView = view.findViewById(R.id.tvTypingIndicator)
        val btnKickUser: ImageButton = view.findViewById(R.id.btnKickUser)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session_user, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]
        
        holder.tvUserName.text = user.userName
        holder.tvUserEmail.text = user.email
        
        // Show creator badge
        holder.tvCreatorBadge.visibility = if (user.isCreator) View.VISIBLE else View.GONE
        
        // Show typing indicator
        holder.tvTypingIndicator.visibility = if (user.isTyping) View.VISIBLE else View.GONE
        
        // Show online/offline status
        val isActive = System.currentTimeMillis() - user.lastActive < 60000 // Active in last minute
        holder.ivUserStatus.setImageResource(
            if (isActive) android.R.drawable.presence_online
            else android.R.drawable.presence_busy
        )
        
        // Show kick button only for creator and not for themselves
        holder.btnKickUser.visibility = if (isCreator && user.userId != currentUserId && !user.isCreator) {
            View.VISIBLE
        } else {
            View.GONE
        }
        
        holder.btnKickUser.setOnClickListener {
            onKickUser(user)
        }
    }

    override fun getItemCount() = users.size

    fun updateUsers(newUsers: List<SessionUser>) {
        users = newUsers
        notifyDataSetChanged()
    }
}
