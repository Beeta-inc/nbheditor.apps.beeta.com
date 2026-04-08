package com.beeta.nbheditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TasksAdapter(
    private var tasks: List<TaskItem>,
    private val onTaskClick: (TaskItem) -> Unit,
    private val onTaskMenuClick: (TaskItem, View) -> Unit
) : RecyclerView.Adapter<TasksAdapter.TaskViewHolder>() {

    class TaskViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTaskTitle: TextView = view.findViewById(R.id.tvTaskTitle)
        val tvTaskDescription: TextView = view.findViewById(R.id.tvTaskDescription)
        val tvAssignedTo: TextView = view.findViewById(R.id.tvAssignedTo)
        val tvTaskStatus: TextView = view.findViewById(R.id.tvTaskStatus)
        val btnTaskMenu: ImageButton = view.findViewById(R.id.btnTaskMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val task = tasks[position]
        
        holder.tvTaskTitle.text = task.title
        holder.tvTaskDescription.text = task.description
        
        // Show assigned user
        holder.tvAssignedTo.text = if (task.assignedTo.isNotEmpty()) {
            "Assigned to: ${task.assignedTo}"
        } else {
            "Unassigned"
        }
        
        // Style status badge
        when (task.status) {
            "next" -> {
                holder.tvTaskStatus.text = "Next"
                holder.tvTaskStatus.setBackgroundColor(0xFF757575.toInt())
            }
            "current" -> {
                holder.tvTaskStatus.text = "Working"
                holder.tvTaskStatus.setBackgroundColor(0xFFFF9800.toInt())
            }
            "completed" -> {
                holder.tvTaskStatus.text = "Done"
                holder.tvTaskStatus.setBackgroundColor(0xFF4CAF50.toInt())
            }
        }
        
        holder.itemView.setOnClickListener { onTaskClick(task) }
        holder.btnTaskMenu.setOnClickListener { onTaskMenuClick(task, it) }
    }

    override fun getItemCount() = tasks.size

    fun updateTasks(newTasks: List<TaskItem>) {
        tasks = newTasks
        notifyDataSetChanged()
    }
}
