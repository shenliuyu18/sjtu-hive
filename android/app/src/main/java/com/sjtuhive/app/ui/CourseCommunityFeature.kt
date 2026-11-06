package com.sjtuhive.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.CourseApi
import com.sjtuhive.app.data.CourseAuthException
import com.sjtuhive.app.data.CourseDetail
import com.sjtuhive.app.data.CourseFilterOption
import com.sjtuhive.app.data.CourseReview
import com.sjtuhive.app.data.CourseSummary
import com.sjtuhive.app.data.CourseUser
import kotlinx.coroutines.launch

@Composable
fun CourseCommunityScreen(context: Context, onClose: () -> Unit) {
    val api = remember { CourseApi(context) }
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<CourseUser?>(null) }
    var checking by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loginBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var hot by remember { mutableStateOf<List<CourseSummary>>(emptyList()) }
    var homeReviews by remember { mutableStateOf<List<CourseReview>>(emptyList()) }
    var followed by remember { mutableStateOf<List<CourseSummary>>(emptyList()) }
    var homeLoading by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var mineMode by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf<List<CourseSummary>>(emptyList()) }
    var minePage by remember { mutableIntStateOf(1) }
    var mineTotal by remember { mutableIntStateOf(0) }
    var mineLoading by remember { mutableStateOf(false) }
    var courses by remember { mutableStateOf<List<CourseSummary>>(emptyList()) }
    var departments by remember { mutableStateOf<List<CourseFilterOption>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var department by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<CourseDetail?>(null) }
    var reviews by remember { mutableStateOf<List<CourseReview>>(emptyList()) }
    var detailLoading by remember { mutableStateOf(false) }

    fun loadCourses(nextPage: Int = 1) {
        scope.launch {
            loading = true
            error = null
            runCatching { api.list(query, nextPage, 20, department, "") }
                .onSuccess {
                    courses = if (nextPage <= 1) it.items else courses + it.items
                    page = it.page
                    total = it.total
                }
                .onFailure { error = it.message ?: "读取课程失败" }
            loading = false
        }
    }

    fun loadMine(nextPage: Int = 1) {
        scope.launch {
            mineLoading = true
            error = null
            runCatching { api.followed(nextPage, 20) }
                .onSuccess {
                    mine = if (nextPage <= 1) it.items else mine + it.items
                    minePage = it.page
                    mineTotal = it.total
                }
                .onFailure { error = it.message ?: "读取我的课程失败" }
            mineLoading = false
        }
    }

    fun loadHome() {
        scope.launch {
            homeLoading = true
            runCatching { api.hot(10) }.onSuccess { hot = it }
            runCatching { api.filters() }.onSuccess { departments = it.departments.take(8) }
            runCatching { api.recentReviews(1, 20) }
                .onSuccess { homeReviews = it.items }
                .onFailure { error = it.message }
            runCatching { api.followed(1, 10) }
                .onSuccess { followed = it.items }
            homeLoading = false
        }
    }

    fun openDetail(course: CourseSummary) {
        detailLoading = true
        scope.launch {
            runCatching { api.detail(course.id) }
                .onSuccess { detail = it }
                .onFailure { error = it.message }
            runCatching { api.reviews(course.id, 1, 12) }
                .onSuccess { reviews = it.items }
            detailLoading = false
        }
    }

    LaunchedEffect(Unit) {
        val current = runCatching { api.state() }.getOrNull()
        user = current
        checking = false
        if (current != null) loadHome()
    }

    BackHandler {
        when {
            mineMode -> mineMode = false
            searchMode -> searchMode = false
            detail != null -> detail = null
            else -> onClose()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                when {
                    mineMode -> mineMode = false
                    searchMode -> searchMode = false
                    detail != null -> detail = null
                    else -> onClose()
                }
            }) { Text("← 返回") }
            Column(Modifier.weight(1f)) {
                Text("选课社区", style = MaterialTheme.typography.titleLarge)
                Text(
                    user?.let { "已登录 ${it.username.ifBlank { it.email }}" } ?: "course.sjtu.plus",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (user != null) {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { api.logout() }
                        user = null
                        courses = emptyList()
                        detail = null
                    }
                }) { Text("退出") }
            }
        }

        when {
            checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            user == null -> LoginPanel(
                email = email,
                password = password,
                busy = loginBusy,
                error = error,
                onEmail = { email = it },
                onPassword = { password = it },
                onLogin = {
                    if (email.isBlank() || password.isBlank()) {
                        error = "请填写邮箱和选课社区密码"
                        return@LoginPanel
                    }
                    loginBusy = true
                    error = null
                    scope.launch {
                        runCatching { api.login(email.trim(), password) }
                            .onSuccess {
                                user = it
                                password = ""
                                loadHome()
                            }
                            .onFailure { error = it.message ?: "登录失败" }
                        loginBusy = false
                    }
                },
            )
            detail != null -> DetailPanel(
                detail = detail!!,
                reviews = reviews,
                loading = detailLoading,
                onBack = { detail = null },
            )
            else -> Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text("搜索课程名、课程代码或教师") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        searchMode = true
                        loadCourses(1)
                    }),
                )
                if (searchMode) {
                    TextButton(onClick = { searchMode = false }) { Text("← 返回首页") }
                    if (departments.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = department.isEmpty(),
                                onClick = { department = ""; loadCourses(1) },
                                label = { Text("全部") },
                            )
                            departments.take(3).forEach { option ->
                                FilterChip(
                                    selected = department == option.name,
                                    onClick = { department = option.name; loadCourses(1) },
                                    label = { Text(option.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }
                    if (loading && courses.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(courses, key = { it.id }) { course ->
                                CourseRow(course) { openDetail(course) }
                            }
                            if (courses.isEmpty() && error == null) {
                                item { Text("没有找到课程。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            if (courses.size < total) {
                                item {
                                    TextButton(
                                        onClick = { loadCourses(page + 1) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(if (loading) "加载中…" else "加载更多（${courses.size}/$total）") }
                                }
                            }
                            error?.let { message ->
                                item {
                                    Text(
                                        message,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                            }
                        }
                    }
                } else if (mineMode) {
                    Column(Modifier.fillMaxSize()) {
                        TextButton(onClick = { mineMode = false }) { Text("← 返回首页") }
                        if (mineLoading && mine.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "我的课程",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            "共 $mineTotal 门",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                items(mine, key = { it.id }) { course ->
                                    CourseRow(course) { openDetail(course) }
                                }
                                if (mine.isEmpty() && error == null) {
                                    item {
                                        Text(
                                            "暂无我的课程。在 course.sjtu.plus 关注课程后会显示在这里。",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (mine.size < mineTotal) {
                                    item {
                                        TextButton(
                                            onClick = { loadMine(minePage + 1) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text(if (mineLoading) "加载中…" else "加载更多（${mine.size}/$mineTotal）") }
                                    }
                                }
                                error?.let { message ->
                                    item {
                                        Text(
                                            message,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (homeLoading && homeReviews.isEmpty() && hot.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(
                                "最新点评",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        items(homeReviews, key = { "review-${it.id}" }) { review ->
                            HomeReviewRow(review) { review.course?.let { openDetail(it) } }
                        }
                        if (hot.isNotEmpty()) {
                            item {
                                Text(
                                    "热门课程",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(vertical = 2.dp),
                                ) {
                                    items(hot) { course -> HotCard(course) { openDetail(course) } }
                                }
                            }
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "我的课程",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                                TextButton(onClick = {
                                    mineMode = true
                                    loadMine(1)
                                }) { Text("查看更多") }
                            }
                        }
                        if (followed.isEmpty()) {
                            item {
                                Text(
                                    "暂无我的课程。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        } else {
                            items(followed, key = { "follow-${it.id}" }) { course ->
                                CourseRow(course) { openDetail(course) }
                            }
                        }
                        error?.let { message ->
                            item {
                                Text(
                                    message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeReviewRow(review: CourseReview, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                review.course?.name ?: "课程评价",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (review.rating > 0) "${review.rating} 分" else review.score,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            review.course?.let { listOf(it.code, it.teacher).filter { v -> v.isNotBlank() }.joinToString(" · ") }
                ?: review.semester,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            review.content.ifBlank { "（无内容）" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(review.semester, review.createdAt.take(10)).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HotCard(course: CourseSummary, onClick: () -> Unit) {
    Column(
        Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(course.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(course.code, course.teacher).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (course.rating.score > 0) "★ %.2f".format(course.rating.score) else "暂无评分",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

private fun openCourse(
    scope: kotlinx.coroutines.CoroutineScope,
    api: CourseApi,
    course: CourseSummary,
    onDetail: (CourseDetail, List<CourseReview>) -> Unit,
    onLoading: (Boolean) -> Unit,
) {
    onLoading(true)
    scope.launch {
        runCatching { api.detail(course.id) }
            .onSuccess { detail ->
                val reviews = runCatching { api.reviews(course.id, 1, 12) }.getOrNull()?.items.orEmpty()
                onDetail(detail, reviews)
            }
        onLoading(false)
    }
}

@Composable
private fun CourseRow(course: CourseSummary, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(course.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                if (course.rating.score > 0) "★ %.2f".format(course.rating.score) else "暂无评分",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            listOf(course.code, course.teacher, course.department).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf("%s 学分".format(course.credit), course.lastSemester, "${course.rating.count} 条评价")
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoginPanel(
    email: String,
    password: String,
    busy: Boolean,
    error: String?,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("登录选课社区", style = MaterialTheme.typography.headlineSmall)
        Text(
            "使用 course.sjtu.plus 的社区账号。这里的密码是选课社区独立密码，不是 jAccount 密码；应用只保存会话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = email,
            onValueChange = onEmail,
            label = { Text("邮箱") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text("选课社区密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = onLogin, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp)) else Unit
            Text(if (busy) "正在登录…" else "登录")
        }
    }
}

@Composable
private fun DetailPanel(
    detail: CourseDetail,
    reviews: List<CourseReview>,
    loading: Boolean,
    onBack: () -> Unit,
) {
    val course = detail.course
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("← 返回列表") }
            Text(course.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                listOf(course.code, course.teacher, course.department, "${course.credit} 学分")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (course.rating.count > 0) "评分 %.2f / 5 · %d 条评价".format(course.rating.score, course.rating.count)
                else "暂无评分",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (detail.moderatorRemark.isNotBlank()) {
                Text(
                    detail.moderatorRemark,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                )
            }
            Text("课程评价", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        }
        if (loading) {
            item { CircularProgressIndicator(Modifier.padding(16.dp)) }
        } else if (reviews.isEmpty()) {
            item { Text("暂无评价。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(reviews, key = { it.id }) { review ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(review.semester.ifBlank { "未知学期" }, style = MaterialTheme.typography.labelSmall)
                        Text(
                            if (review.rating > 0) "${review.rating} 分" else review.score,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(review.content.ifBlank { "（无内容）" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
