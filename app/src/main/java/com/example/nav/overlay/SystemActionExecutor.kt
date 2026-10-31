package com.example.nav.overlay

import android.content.Context
import android.content.Intent

class SystemActionExecutor(private val context: Context) {
    fun openAppByName(appName: String): Boolean {
        if (appName.isBlank()) return false
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
        val packageName = AppMatcher.match(appName, apps) ?: return false
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}

internal object AppMatcher {
    fun match(query: String, apps: List<Pair<String, String>>): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val exact = apps.filter { it.first.lowercase() == q || it.second.lowercase() == q }
        if (exact.isNotEmpty()) return exact.singleOrNull()?.first
        // Only whole label words, and only when unambiguous. "mail" must not launch a random app.
        return apps.filter { (_, label) ->
            Regex("(?:^|\\s)${Regex.escape(q)}(?:$|\\s)").containsMatchIn(label.lowercase())
        }.singleOrNull()?.first
    }
}
