package com.dsh.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 桥接返回的错误。
 *
 * 带上 HTTP 状态码是有原因的：404 意味着"这个东西在电脑上已经不存在了"
 * （比如 harness 重启、插件重载、终端被空闲回收），
 * 那要**重开一个**；而超时/断流是"暂时联系不上"，重连就行。
 * 不区分的话只能一直重试一个已经没了的终端。
 */
class BridgeException(message: String, val code: Int = 0, cause: Throwable? = null) : IOException(message, cause)

/**
 * 与 PC 侧 `dsh-phone-bridge` 通信的客户端。
 *
 * 普通请求走阻塞式 HTTP（调用方已在 IO 调度器上）；
 * 实时流走 SSE —— 用长连接把 harness 的状态变化和模型输出增量推过来。
 */
class BridgeClient(private val baseUrl: String, private val token: String) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** SSE 需要无限读超时，否则空闲时会被自己掐断。 */
    private val sseHttp: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun url(path: String): String = buildUrl(baseUrl, token, path)

    /** 失败时优先把桥接返回的 error 字段抬出来 —— 比"HTTP 404: {...}"有用得多。 */
    private fun failure(code: Int, body: String): BridgeException {
        val msg = runCatching { JSONObject(body).optString("error", "") }.getOrDefault("")
        return BridgeException(if (msg.isNotBlank()) msg else "HTTP $code: ${body.take(200)}", code = code)
    }

    private fun get(path: String): JSONObject {
        val req = Request.Builder().url(url(path)).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw failure(resp.code, body)
            return JSONObject(body)
        }
    }

    private fun post(path: String, payload: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(url(path))
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw failure(resp.code, body)
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    /* --------------------------- 普通接口 --------------------------- */

    suspend fun ping(): Long = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        get("/phone/health")
        System.currentTimeMillis() - t0
    }

    /**
     * watching 非空时，顺带把「手机正开着这个会话」告诉桥接。
     * 这是问答功能的心跳来源 —— 搭在本来就要发的状态轮询上，不额外加请求。
     */
    suspend fun state(max: Int = 60, watching: String? = null): HarnessState = withContext(Dispatchers.IO) {
        val w = if (watching.isNullOrBlank()) "" else "&watching=" + URLEncoder.encode(watching, "UTF-8")
        parseState(get("/phone/state?max=$max$w"))
    }

    suspend fun history(sessionId: String, max: Int = 40): List<ChatMessage> = withContext(Dispatchers.IO) {
        val root = get("/phone/sessions/$sessionId/history?max=$max")
        parseMessages(root.optJSONArray("messages"))
    }

    suspend fun prompt(sessionId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("text", text)
        post("/phone/sessions/$sessionId/prompt", payload).optBoolean("accepted", false)
    }

    suspend fun cancel(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        post("/phone/sessions/$sessionId/cancel", JSONObject()).optBoolean("ok", false)
    }

    suspend fun newSession(cwd: String? = null, workspaceId: String? = null): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
        if (!cwd.isNullOrBlank()) payload.put("cwd", cwd)
        if (!workspaceId.isNullOrBlank()) payload.put("workspaceId", workspaceId)
        post("/phone/new", payload).optString("sessionId", "")
    }

    /* ------------------------- 工作区 / 目录 ------------------------- */

    /** 电脑上的历史 + 当前工作区，附带各自会话。 */
    suspend fun workspaces(): List<WorkspaceInfo> = withContext(Dispatchers.IO) {
        parseWorkspaces(get("/phone/workspaces"))
    }

    /** 浏览电脑上的目录；path 为空则从家目录开始。 */
    suspend fun fsList(path: String? = null): DirListing = withContext(Dispatchers.IO) {
        val q = if (path.isNullOrBlank()) "" else "?path=" + URLEncoder.encode(path, "UTF-8")
        parseDirListing(get("/phone/fs/list" + q))
    }

    /** 在电脑上新建一个文件夹，返回其绝对路径。 */
    suspend fun fsMkdir(parent: String, name: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("parent", parent).put("name", name)
        val r = post("/phone/fs/mkdir", payload)
        if (!r.optBoolean("ok", false)) throw BridgeException(r.optString("error", "创建文件夹失败"))
        r.optString("path", "")
    }

    /* ------------------------- 会话/工作区管理 ------------------------- */

    suspend fun renameSession(sessionId: String, title: String): String = withContext(Dispatchers.IO) {
        val r = post("/phone/sessions/$sessionId/rename", JSONObject().put("title", title))
        if (!r.optBoolean("ok", false)) throw BridgeException(r.optString("error", "重命名失败"))
        r.optString("title", title)
    }

    /** 分支会话：返回新会话 id。 */
    suspend fun forkSession(sessionId: String): String = withContext(Dispatchers.IO) {
        val r = post("/phone/sessions/$sessionId/fork", JSONObject())
        if (!r.optBoolean("ok", false)) throw BridgeException(r.optString("error", "分支失败"))
        r.optString("sessionId", "")
    }

    /** 归档会话（harness 没有删除会话，日志是只追加的）。 */
    suspend fun archiveSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val r = post("/phone/sessions/$sessionId/archive", JSONObject())
        if (!r.optBoolean("ok", false)) throw BridgeException(r.optString("error", "归档失败"))
        r.optBoolean("archived", false)
    }

    suspend fun renameWorkspace(workspaceId: String, title: String): String = withContext(Dispatchers.IO) {
        val r = post("/phone/workspaces/$workspaceId/rename", JSONObject().put("title", title))
        if (!r.optBoolean("ok", false)) throw BridgeException(r.optString("error", "重命名失败"))
        r.optString("title", title)
    }

    /** 删除工作区注册（目录与会话日志都保留）。 */
    suspend fun deleteWorkspace(workspaceId: String): Boolean = withContext(Dispatchers.IO) {
        val r = post("/phone/workspaces/$workspaceId/delete", JSONObject())
        if (!r.optBoolean("ok", false)) throw BridgeException(r.optString("error", "删除失败"))
        r.optBoolean("deleted", false)
    }

    /** 读一个文本文件（相对路径会按该会话的 cwd 解析）。 */
    suspend fun fileText(path: String, sessionId: String? = null, max: Int? = null): FileText =
        withContext(Dispatchers.IO) {
            val q = StringBuilder("?path=").append(URLEncoder.encode(path, "UTF-8"))
            if (!sessionId.isNullOrBlank()) {
                q.append("&sessionId=").append(URLEncoder.encode(sessionId, "UTF-8"))
            }
            if (max != null) q.append("&max=").append(max)
            val r = get("/phone/file$q")
            if (!r.optBoolean("ok", false)) throw BridgeException(r.optString("error", "读取文件失败"))
            FileText(
                path = r.optString("path", path),
                text = r.optString("text", ""),
                size = r.optInt("size", 0),
                truncated = r.optBoolean("truncated", false),
            )
        }

    /* ------------------------------ 模型 ------------------------------ */

    /** 主机侧可路由的模型目录；带上 sessionId 时还返回该会话当前的选择。 */
    suspend fun models(sessionId: String? = null): ModelCatalog = withContext(Dispatchers.IO) {
        val q = if (sessionId.isNullOrBlank()) "" else "?sessionId=" + URLEncoder.encode(sessionId, "UTF-8")
        parseModelCatalog(get("/phone/models" + q))
    }

    /** 切换某个会话的模型（也可以只换推理强度）。 */
    suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        effort: String? = null,
    ): ModelSelection = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("sessionId", sessionId).put("provider", provider).put("model", model)
        if (!effort.isNullOrBlank()) payload.put("reasoningEffort", effort)
        val r = post("/phone/model", payload)
        if (!r.optBoolean("ok", false)) throw BridgeException(r.optString("error", "切换模型失败"))
        parseModelSelection(r.optJSONObject("selected"))
            ?: ModelSelection(provider, model, effort)
    }

    /** 回答 harness 提出的问题（ask_user_question）。 */
    suspend fun answer(questionId: String, answers: List<QuestionAnswer>): Boolean = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        for (a in answers) {
            val o = JSONObject().put("id", a.id).put("selected", JSONArray(a.selected))
            if (!a.custom.isNullOrBlank()) o.put("custom", a.custom)
            arr.put(o)
        }
        val payload = JSONObject().put("id", questionId).put("answers", arr)
        val r = post("/phone/answer", payload)
        r.optBoolean("ok", false)
    }

    /** 把一个已存在的目录登记成 harness 工作区。 */
    suspend fun createWorkspace(path: String, title: String? = null): WorkspaceInfo = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("path", path)
        if (!title.isNullOrBlank()) payload.put("title", title)
        val r = post("/phone/workspaces/create", payload)
        if (!r.optBoolean("ok", false)) throw BridgeException(r.optString("error", "创建会话工作区失败"))
        WorkspaceInfo(
            id = r.optString("id", ""),
            path = r.optString("path", path),
            title = r.optString("title", title ?: path.substringAfterLast('/')),
            sessionCount = 0,
            runningCount = 0,
            updatedAt = null,
            sessions = emptyList(),
        )
    }

    /* ---------------------------- 排队消息 ---------------------------- */

    /** 还没轮到执行的排队消息（跑着的时候发出去的那几条）。 */
    suspend fun queue(sessionId: String): List<QueuedMessage> = withContext(Dispatchers.IO) {
        parseQueue(get("/phone/sessions/$sessionId/queue").optJSONArray("items"))
    }

    /**
     * 改 / 撤 / 插话一条排队消息。
     *
     * action: "remove" 撤回 ｜ "edit" 改内容（要带 text）｜ "steer" 让它下轮立刻带上
     */
    suspend fun queueAction(sessionId: String, itemId: String, action: String, text: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("action", action)
            if (text != null) payload.put("text", text)
            post("/phone/sessions/$sessionId/queue/$itemId", payload).optBoolean("ok", false)
        }

    /* ----------------------------- 终端 ----------------------------- */

    /**
     * 在电脑上开一个真 PTY。
     *
     * cols/rows 在分配时就定死了（PTY 没有 resize 接口），所以要按手机屏幕
     * 实际能放多少列来传 —— 列数不对，`ls`、表格、提示符全都会折行。
     */
    suspend fun termOpen(cols: Int, rows: Int, cwd: String? = null): TermOpenResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("cols", cols).put("rows", rows)
        if (!cwd.isNullOrBlank()) payload.put("cwd", cwd)
        val r = post("/phone/term/open", payload)
        TermOpenResult(
            id = r.optString("id", ""),
            pid = r.optInt("pid", 0),
            cwd = r.optString("cwd", ""),
            cols = r.optInt("cols", cols),
            rows = r.optInt("rows", rows),
        )
    }

    /** 键盘输入：data 里可以带 `\n`（回车）、`\u0003` 等控制字符。 */
    suspend fun termInput(id: String, data: String): Boolean = withContext(Dispatchers.IO) {
        post("/phone/term/$id/input", JSONObject().put("data", data)).optBoolean("ok", false)
    }

    /** 给前台进程组发信号（Ctrl-C 走这个，不是把终端整个关掉）。 */
    suspend fun termSignal(id: String, signal: String): Boolean = withContext(Dispatchers.IO) {
        post("/phone/term/$id/signal", JSONObject().put("signal", signal)).optBoolean("ok", false)
    }

    suspend fun termClose(id: String): Boolean = withContext(Dispatchers.IO) {
        post("/phone/term/$id/close", JSONObject()).optBoolean("ok", false)
    }

    /** 终端输出流（SSE）：先来一帧 hello（带缓冲），再是 out / exit / closed。 */
    fun openTermStream(
        id: String,
        onEvent: (event: String, data: JSONObject) -> Unit,
        onClosed: (Throwable?) -> Unit,
    ): Call = openSse("/phone/term/$id/stream", onEvent, onClosed)

    /* ----------------------------- SSE ----------------------------- */

    /**
     * 打开一条 SSE 长连接。回调在 OkHttp 的调度线程上执行，调用方自行切线程。
     * 返回 Call 以便取消（取消会触发 onClosed）。
     */
    fun openSse(
        path: String,
        onEvent: (event: String, data: JSONObject) -> Unit,
        onClosed: (Throwable?) -> Unit,
    ): Call {
        val req = Request.Builder()
            .url(url(path))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        val call = sseHttp.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onClosed(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null) {
                        // 尽量把桥接写的 error 抬出来：界面直接显示"终端不存在或已关闭"
                        // 比"HTTP 404"有用得多
                        val text = runCatching { body?.string().orEmpty() }.getOrDefault("")
                        val msg = runCatching { JSONObject(text).optString("error", "") }.getOrDefault("")
                        onClosed(
                            BridgeException(
                                if (msg.isNotBlank()) msg else "HTTP ${resp.code}",
                                code = resp.code,
                            ),
                        )
                        return
                    }
                    var event = "message"
                    val data = StringBuilder()
                    try {
                        val source = body.source()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> {
                                    if (data.isNotEmpty()) {
                                        val payload = runCatching { JSONObject(data.toString()) }.getOrNull()
                                        if (payload != null) onEvent(event, payload)
                                        data.setLength(0)
                                    }
                                    event = "message"
                                }
                                line.startsWith(":") -> Unit
                                line.startsWith("event:") -> event = line.substring(6).trim()
                                line.startsWith("data:") -> {
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(line.substring(5).trim())
                                }
                            }
                        }
                        onClosed(null)
                    } catch (e: IOException) {
                        onClosed(e)
                    }
                }
            }
        })
        return call
    }

    companion object {
        /** 拼 URL，并把令牌作为查询参数带上。 */
        fun buildUrl(baseUrl: String, token: String, path: String): String {
            val base = baseUrl.trim().trimEnd('/')
            if (token.isBlank()) return base + path
            val sep = if (path.contains('?')) '&' else '?'
            return base + path + sep + "token=" + URLEncoder.encode(token, "UTF-8")
        }

        /**
         * 快速探活：用很短的超时去戳 /phone/health，用于在多个端点里挑可用的那个。
         *
         * 超时必须短 —— 在家里第一个端点（USB）秒回，在外面第一个端点会
         * 连接超时，这时不能让用户干等默认的 8 秒再去试第二个。
         */
        fun probe(baseUrl: String, token: String, timeoutMs: Long = 2500L): Boolean {
            val quick = OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            return try {
                val req = Request.Builder().url(buildUrl(baseUrl, token, "/phone/health")).get().build()
                quick.newCall(req).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }
    }
}
