package com.beeta.nbheditor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView

/**
 * Controller for Nir Eyal Hook Micro-Interactions: Variable Reward Phase.
 *
 * Provides subtle gamification feedback for:
 * 1. Writing Streaks (habit loops, positive reinforcement)
 * 2. Word Milestones (flow-state recognition, sense of accomplishment)
 * 3. Session Sync Status (peace of mind, perceived real-time reliability)
 *
 * Animates smoothly into view and automatically fades out after a short duration.
 */
class VariableRewardBannerController(
    private val rootBannerView: View
) {
    private val card: View = rootBannerView.findViewById(R.id.cardVariableReward) ?: rootBannerView
    private val ivIcon: ImageView? = rootBannerView.findViewById(R.id.ivRewardIcon)
    private val tvTitle: TextView? = rootBannerView.findViewById(R.id.tvRewardTitle)
    private val tvSubtitle: TextView? = rootBannerView.findViewById(R.id.tvRewardSubtitle)
    private val tvMetric: TextView? = rootBannerView.findViewById(R.id.tvRewardMetric)
    private val btnDismiss: View? = rootBannerView.findViewById(R.id.btnRewardDismiss)

    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    init {
        btnDismiss?.setOnClickListener {
            dismiss(smooth = true)
        }
        card.setOnClickListener {
            dismiss(smooth = true)
        }
    }

    /**
     * Show streak achievement (Variable Reward: Tribe / Self)
     */
    fun showWritingStreak(streakDays: Int, wordsToday: Int = 0, autoDismissMs: Long = 3500L) {
        val title = when (streakDays) {
            1 -> "1-Day Streak Started!"
            else -> "$streakDays-Day Writing Streak! 🔥"
        }
        val sub = if (wordsToday > 0) {
            "$wordsToday words written today • Unstoppable flow"
        } else {
            "Consistency fuels mastery • Momentum unlocked"
        }
        val metric = "🔥 ${streakDays}d"
        show(
            iconRes = R.drawable.ic_reward_streak,
            title = title,
            subtitle = sub,
            metric = metric,
            autoDismissMs = autoDismissMs
        )
    }

    /**
     * Show word count milestone (Variable Reward: Mastery / Hunt)
     */
    fun showWordMilestone(wordCount: Int, autoDismissMs: Long = 3500L) {
        val title = "Milestone Unlocked! 🏆"
        val sub = "Reached $wordCount words in this session"
        val metric = "${wordCount}w"
        show(
            iconRes = R.drawable.ic_reward_milestone,
            title = title,
            subtitle = sub,
            metric = metric,
            autoDismissMs = autoDismissMs
        )
    }

    /**
     * Show session sync status (Variable Reward: Certainty / Relief)
     */
    fun showSessionSync(status: String = "Session Synced ⚡", detail: String = "All changes securely saved", autoDismissMs: Long = 3000L) {
        show(
            iconRes = R.drawable.ic_reward_sync,
            title = status,
            subtitle = detail,
            metric = "Saved",
            autoDismissMs = autoDismissMs
        )
    }

    fun show(
        iconRes: Int,
        title: String,
        subtitle: String,
        metric: String? = null,
        autoDismissMs: Long = 3500L
    ) {
        dismissRunnable?.let { handler.removeCallbacks(it) }

        ivIcon?.setImageResource(iconRes)
        tvTitle?.text = title
        tvSubtitle?.text = subtitle

        if (metric != null) {
            tvMetric?.text = metric
            tvMetric?.visibility = View.VISIBLE
        } else {
            tvMetric?.visibility = View.GONE
        }

        if (rootBannerView.visibility != View.VISIBLE) {
            val fadeIn = AnimationUtils.loadAnimation(rootBannerView.context, R.anim.reward_banner_fade_in)
            rootBannerView.visibility = View.VISIBLE
            rootBannerView.startAnimation(fadeIn)
        }

        if (autoDismissMs > 0) {
            val runnable = Runnable { dismiss(smooth = true) }
            dismissRunnable = runnable
            handler.postDelayed(runnable, autoDismissMs)
        }
    }

    fun dismiss(smooth: Boolean = true) {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null

        if (rootBannerView.visibility != View.VISIBLE) return

        if (smooth) {
            val fadeOut = AnimationUtils.loadAnimation(rootBannerView.context, R.anim.reward_banner_fade_out)
            fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    rootBannerView.visibility = View.GONE
                }
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            })
            rootBannerView.startAnimation(fadeOut)
        } else {
            rootBannerView.visibility = View.GONE
        }
    }

    companion object {
        fun inflate(parent: ViewGroup, attachToParent: Boolean = false): View {
            val inflater = LayoutInflater.from(parent.context)
            return inflater.inflate(R.layout.view_variable_reward_banner, parent, attachToParent)
        }
    }
}
