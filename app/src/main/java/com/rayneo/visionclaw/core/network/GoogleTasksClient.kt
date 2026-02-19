package com.rayneo.visionclaw.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GoogleTasksClient – fetches and manages Google Tasks via the Tasks API v1.
 *
 * Requires OAuth Bearer token (Tasks API does not support API key auth).
 *
 * Error-handling contract:
 *   • No access token → [TasksResult.AuthRequired]
 *   • Network or server errors → [TasksResult.Error] with message
 *   • Success → [TasksResult.Success] with list of tasks
 */
class GoogleTasksClient(
    private val accessTokenProvider: () -> String?
) {

    companion object {
        private const val TAG = "GoogleTasks"
        private const val BASE_URL = "https://www.googleapis.com/tasks/v1"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    // ── Data classes ──────────────────────────────────────────────────────

    data class TaskItem(
        val id: String,
        val title: String,
        val notes: String?,
        val due: Date?,
        val status: String,           // "needsAction" or "completed"
        val completed: Date?,
        val position: String?
    )

    data class TaskListInfo(
        val id: String,
        val title: String,
        val updated: String?
    )

    // ── Result types ──────────────────────────────────────────────────────

    sealed class TasksResult {
        data class Success(val tasks: List<TaskItem>) : TasksResult()
        data class Error(val message: String, val code: Int = -1) : TasksResult()
        object AuthRequired : TasksResult()
    }

    sealed class TaskListsResult {
        data class Success(val lists: List<TaskListInfo>) : TaskListsResult()
        data class Error(val message: String) : TaskListsResult()
        object AuthRequired : TaskListsResult()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Fetch all task lists for the authenticated user.
     */
    suspend fun fetchTaskLists(): TaskListsResult = withContext(Dispatchers.IO) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) return@withContext TaskListsResult.AuthRequired

        try {
            val conn = (URL("$BASE_URL/users/@me/lists").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $accessToken")
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val json = JSONObject(body)
                val items = json.optJSONArray("items")
                    ?: return@withContext TaskListsResult.Success(emptyList())

                val lists = (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    TaskListInfo(
                        id = item.optString("id", ""),
                        title = item.optString("title", "(Untitled)"),
                        updated = item.optString("updated", null)
                    )
                }
                Log.d(TAG, "Fetched ${lists.size} task lists")
                TaskListsResult.Success(lists)
            } else {
                val errorBody = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                Log.e(TAG, "TaskLists HTTP $code: $errorBody")
                TaskListsResult.Error("Task lists error ($code)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TaskLists request failed", e)
            TaskListsResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Fetch incomplete tasks from a specific task list.
     *
     * @param taskListId  The task list ID (use "@default" for the user's default list).
     * @param maxResults  Maximum number of tasks to return.
     */
    suspend fun fetchTasks(
        taskListId: String = "@default",
        maxResults: Int = 10
    ): TasksResult = withContext(Dispatchers.IO) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) return@withContext TasksResult.AuthRequired

        try {
            val urlStr = "$BASE_URL/lists/$taskListId/tasks" +
                "?maxResults=$maxResults" +
                "&showCompleted=false" +
                "&showHidden=false"

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $accessToken")
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val json = JSONObject(body)
                val items = json.optJSONArray("items")
                    ?: return@withContext TasksResult.Success(emptyList())

                val tasks = (0 until items.length()).mapNotNull { i ->
                    val item = items.getJSONObject(i)
                    val title = item.optString("title", "").trim()
                    if (title.isBlank()) return@mapNotNull null  // skip empty tasks

                    TaskItem(
                        id = item.optString("id", ""),
                        title = title,
                        notes = item.optString("notes", null)?.takeIf { it.isNotBlank() },
                        due = parseRfc3339Date(item.optString("due", "")),
                        status = item.optString("status", "needsAction"),
                        completed = parseRfc3339Date(item.optString("completed", "")),
                        position = item.optString("position", null)
                    )
                }
                Log.d(TAG, "Fetched ${tasks.size} tasks from list $taskListId")
                TasksResult.Success(tasks)
            } else {
                val errorBody = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                Log.e(TAG, "Tasks HTTP $code: $errorBody")
                TasksResult.Error("Tasks error ($code)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tasks request failed", e)
            TasksResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Create a new task in the given list.
     */
    suspend fun createTask(
        taskListId: String = "@default",
        title: String,
        notes: String? = null,
        dueDate: String? = null
    ): TasksResult = withContext(Dispatchers.IO) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) return@withContext TasksResult.AuthRequired

        try {
            val taskJson = JSONObject().apply {
                put("title", title)
                if (!notes.isNullOrBlank()) put("notes", notes)
                if (!dueDate.isNullOrBlank()) put("due", dueDate)
            }

            val conn = (URL("$BASE_URL/lists/$taskListId/tasks").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(taskJson.toString()) }

            val code = conn.responseCode
            if (code in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val item = JSONObject(body)
                val created = TaskItem(
                    id = item.optString("id", ""),
                    title = item.optString("title", title),
                    notes = notes,
                    due = parseRfc3339Date(item.optString("due", "")),
                    status = item.optString("status", "needsAction"),
                    completed = null,
                    position = item.optString("position", null)
                )
                TasksResult.Success(listOf(created))
            } else {
                val errorBody = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                Log.e(TAG, "Create task HTTP $code: $errorBody")
                TasksResult.Error("Failed to create task ($code)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Create task failed", e)
            TasksResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Mark a task as completed.
     */
    suspend fun completeTask(
        taskListId: String = "@default",
        taskId: String
    ): TasksResult = withContext(Dispatchers.IO) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) return@withContext TasksResult.AuthRequired

        try {
            val patchJson = JSONObject().apply {
                put("status", "completed")
            }

            val conn = (URL("$BASE_URL/lists/$taskListId/tasks/$taskId").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"   // PATCH via POST with override header
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
                doOutput = true
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(patchJson.toString()) }

            val code = conn.responseCode
            if (code in 200..299) {
                TasksResult.Success(emptyList())
            } else {
                val errorBody = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                Log.e(TAG, "Complete task HTTP $code: $errorBody")
                TasksResult.Error("Failed to complete task ($code)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Complete task failed", e)
            TasksResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    // ── Date parsing ──────────────────────────────────────────────────────

    private fun parseRfc3339Date(dateStr: String): Date? {
        if (dateStr.isBlank()) return null
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                return SimpleDateFormat(fmt, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(dateStr)
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }
}
