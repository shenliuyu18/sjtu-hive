import { BookOpen, LogOut, Search, Star } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import { call } from '@/lib/bridge';
import { cn } from '@/lib/cn';
import { formatDate } from '@/lib/format';
import { Badge, Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';

type CourseUser = { id: number; username: string; email: string; role: string };
type CourseState = { loggedIn: boolean; user: CourseUser | null };
type Rating = { count: number; avg: number; score: number; distribution: number[] };
type Teacher = { id: number; code: string; name: string; department: string; title: string };
type CourseSummary = {
  id: number;
  code: string;
  name: string;
  credit: number;
  department: string;
  language: string;
  target_years: string[];
  categories: string[] | null;
  last_semester: string;
  main_teacher: Teacher | null;
  rating: Rating;
};
type CourseHot = { items: { course: CourseSummary; score: number }[] };
type CourseFilters = {
  departments: { name: string; count: number }[];
  semesters: { name: string; count: number }[];
};
type CourseList = { items: CourseSummary[]; total: number; page: number; page_size: number };
type CourseEnrollment = { id: number; semester: string; course: CourseSummary };
type CourseDetail = CourseSummary & {
  moderator_remark: string;
  offered_courses: { semester: string; language: string; target_years: string[] }[];
  notification_level: number;
};
type Review = {
  id: number;
  course?: CourseSummary | null;
  semester: string;
  score: string;
  rating: number;
  content: string;
  moderator_remark: string;
  created_at: string;
};
type ReviewList = { items: Review[]; total: number; page: number; page_size: number };

function ratingText(rating?: Rating | null) {
  if (!rating || !rating.count) return '暂无评分';
  return `${rating.score.toFixed(2)} / 5 · ${rating.count} 条`;
}

function teacherOf(course?: CourseSummary | null) {
  return course?.main_teacher?.name || '未知教师';
}

function CourseCard({
  course,
  active,
  onClick,
}: {
  course: CourseSummary;
  active?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'w-full rounded-xl border border-line bg-surface px-4 py-3 text-left transition-colors hover:border-line-strong',
        active && 'border-accent/50 bg-accent/5',
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-ink">{course.name}</div>
          <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[0.66rem] text-ink-3">
            <span className="font-mono">{course.code}</span>
            <span>· {teacherOf(course)}</span>
            {course.department ? <span>· {course.department}</span> : null}
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-1 text-[0.68rem] text-warn">
          <Star className="size-3 fill-current" />
          {course.rating?.score ? course.rating.score.toFixed(1) : '-'}
        </div>
      </div>
      <div className="mt-2 flex items-center gap-2 text-[0.64rem] text-ink-3">
        <span>{course.credit} 学分</span>
        {course.last_semester ? <span>· {course.last_semester}</span> : null}
        <span>· {ratingText(course.rating)}</span>
      </div>
    </button>
  );
}

function CompactCourse({ course, onClick }: { course: CourseSummary; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-center justify-between gap-3 rounded-lg border border-line bg-surface px-3 py-2 text-left transition-colors hover:border-line-strong"
    >
      <div className="min-w-0">
        <div className="truncate text-[0.72rem] font-medium text-ink">{course.name}</div>
        <div className="truncate text-[0.62rem] text-ink-3">
          {course.code} · {teacherOf(course)}
        </div>
      </div>
      <span className="shrink-0 text-[0.66rem] text-warn">
        ★ {course.rating?.score ? course.rating.score.toFixed(2) : '-'}
      </span>
    </button>
  );
}

function ReviewRow({ review, onClick }: { review: Review; onClick: () => void }) {
  const course = review.course;
  return (
    <button
      type="button"
      onClick={onClick}
      className="w-full rounded-xl border border-line bg-surface px-4 py-3 text-left transition-colors hover:border-line-strong"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-ink">{course?.name || '课程评价'}</div>
          <div className="mt-0.5 truncate text-[0.64rem] text-ink-3">
            {course ? `${course.code} · ${teacherOf(course)}` : review.semester}
          </div>
        </div>
        <Badge variant="warning">
          <Star className="mr-1 size-3 fill-current" />
          {review.rating || review.score || '-'}
        </Badge>
      </div>
      <div className="mt-2 line-clamp-3 whitespace-pre-wrap text-[0.7rem] leading-relaxed text-ink-2">
        {review.content || '（无内容）'}
      </div>
      <div className="mt-2 flex items-center justify-between text-[0.6rem] text-ink-3">
        <span>{review.semester || '未知学期'}</span>
        <span>{review.created_at ? formatDate(review.created_at, 'yyyy-MM-dd') : ''}</span>
      </div>
    </button>
  );
}

function CourseDetailPanel({
  detail,
  reviews,
  loading,
}: {
  detail: CourseDetail;
  reviews: ReviewList | null;
  loading: boolean;
}) {
  if (loading) {
    return (
      <div className="flex h-40 items-center justify-center gap-2 text-xs text-ink-3">
        <Spinner /> 正在读取课程详情…
      </div>
    );
  }
  return (
    <>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-base font-semibold text-ink">{detail.name}</div>
          <div className="mt-0.5 text-[0.66rem] text-ink-3">
            {detail.code} · {detail.credit} 学分 · {detail.department}
          </div>
        </div>
        <Badge variant="warning">
          <Star className="mr-1 size-3 fill-current" />
          {detail.rating?.score ? detail.rating.score.toFixed(2) : '-'}
        </Badge>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2 text-[0.66rem] text-ink-2">
        <div>教师：{teacherOf(detail)}</div>
        <div>最近学期：{detail.last_semester || '-'}</div>
        <div>语言：{detail.language || '-'}</div>
        <div>评价：{ratingText(detail.rating)}</div>
      </div>
      {detail.moderator_remark ? (
        <div className="mt-3 rounded-lg border border-line bg-surface p-3 text-[0.68rem] leading-relaxed text-ink-2">
          {detail.moderator_remark}
        </div>
      ) : null}
      <div className="mt-4 text-[0.7rem] font-medium text-ink">课程评价</div>
      <div className="mt-2 space-y-2">
        {reviews?.items?.length ? (
          reviews.items.map((review) => (
            <div key={review.id} className="rounded-lg border border-line bg-surface p-3">
              <div className="flex items-center justify-between text-[0.62rem] text-ink-3">
                <span>{review.semester || '未知学期'}</span>
                <span>{review.rating ? `${review.rating} 分` : review.score}</span>
              </div>
              <div className="mt-1.5 whitespace-pre-wrap text-[0.7rem] leading-relaxed text-ink-2">
                {review.content || '（无内容）'}
              </div>
              <div className="mt-1.5 text-[0.6rem] text-ink-3">
                {review.created_at ? formatDate(review.created_at, 'yyyy-MM-dd') : ''}
              </div>
            </div>
          ))
        ) : (
          <div className="text-[0.68rem] text-ink-3">暂无评价。</div>
        )}
      </div>
    </>
  );
}

export function CourseCommunityPage() {
  const [state, setState] = useState<CourseState | null>(null);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loginBusy, setLoginBusy] = useState(false);
  const [hot, setHot] = useState<CourseHot | null>(null);
  const [filters, setFilters] = useState<CourseFilters | null>(null);
  const [homeReviews, setHomeReviews] = useState<ReviewList | null>(null);
  const [mode, setMode] = useState<'home' | 'search' | 'mine'>('home');
  const [mine, setMine] = useState<CourseList | null>(null);
  const [ignored, setIgnored] = useState<CourseList | null>(null);
  const [enrollments, setEnrollments] = useState<CourseEnrollment[]>([]);
  const [mineTab, setMineTab] = useState<'enrolled' | 'followed' | 'ignored'>('enrolled');
  const [mineSemester, setMineSemester] = useState('');
  const [mineLoading, setMineLoading] = useState(false);
  const [list, setList] = useState<CourseList | null>(null);
  const [query, setQuery] = useState('');
  const [department, setDepartment] = useState('');
  const [semester, setSemester] = useState('');
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<CourseDetail | null>(null);
  const [reviews, setReviews] = useState<ReviewList | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const loadHome = useCallback(async () => {
    const [reviewResult, enrolledResult] = await Promise.allSettled([
      call<ReviewList>('course.recentReviews', 1, 20),
      call<CourseEnrollment[]>('course.enrolled'),
    ]);
    if (reviewResult.status === 'fulfilled') setHomeReviews(reviewResult.value);
    if (enrolledResult.status === 'fulfilled') setEnrollments(enrolledResult.value ?? []);
  }, []);

  const loadEnrolled = useCallback(async () => {
    setMineLoading(true);
    try {
      setEnrollments(await call<CourseEnrollment[]>('course.enrolled'));
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取选课记录失败');
    } finally {
      setMineLoading(false);
    }
  }, []);

  const loadIgnored = useCallback(async (nextPage = 1) => {
    setMineLoading(true);
    try {
      setIgnored(await call<CourseList>('course.ignored', nextPage, 20));
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取屏蔽课程失败');
    } finally {
      setMineLoading(false);
    }
  }, []);

  const loadMine = useCallback(async (nextPage = 1) => {
    setMineLoading(true);
    try {
      setMine(await call<CourseList>('course.followed', nextPage, 20));
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取我的课程失败');
    } finally {
      setMineLoading(false);
    }
  }, []);

  const loadBase = useCallback(async () => {
    const [hotResult, filterResult] = await Promise.allSettled([
      call<CourseHot>('course.hot', 'week', 10),
      call<CourseFilters>('course.filters'),
    ]);
    if (hotResult.status === 'fulfilled') setHot(hotResult.value);
    if (filterResult.status === 'fulfilled') setFilters(filterResult.value);
  }, []);

  const loadList = useCallback(
    async (nextPage = page) => {
      setLoading(true);
      try {
        const result = await call<CourseList>(
          'course.list',
          query.trim(),
          nextPage,
          20,
          department,
          semester,
          '',
        );
        setList(result);
        setPage(result?.page ?? nextPage);
      } catch (cause) {
        toast.error(cause instanceof Error ? cause.message : '读取课程失败');
      } finally {
        setLoading(false);
      }
    },
    [department, page, query, semester],
  );

  useEffect(() => {
    void call<CourseState>('course.state')
      .then((value) => {
        // call 返回 null（如浏览器预览模式下无 mock 实现）时也要收尾，避免一直停在加载态
        setState(value ?? { loggedIn: false, user: null });
        if (value?.loggedIn) {
          void loadBase();
          void loadHome();
        }
      })
      .catch(() => setState({ loggedIn: false, user: null }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openCourse = async (course: CourseSummary) => {
    setSelectedId(course.id);
    setDetailLoading(true);
    try {
      const [detailResult, reviewResult] = await Promise.all([
        call<CourseDetail>('course.detail', course.id),
        call<ReviewList>('course.reviews', course.id, 1, 10, '', ''),
      ]);
      setDetail(detailResult);
      setReviews(reviewResult);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取课程详情失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const login = async () => {
    if (!email.trim() || !password) {
      toast.error('请填写邮箱和选课社区密码');
      return;
    }
    setLoginBusy(true);
    try {
      await call<CourseUser>('course.login', email.trim(), password);
      setPassword('');
      setState(await call<CourseState>('course.state'));
      toast.success('已登录选课社区');
      void loadBase();
      void loadHome();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '登录失败');
    } finally {
      setLoginBusy(false);
    }
  };

  const logout = async () => {
    try {
      await call('course.logout');
      setState({ loggedIn: false, user: null });
      setList(null);
      setDetail(null);
      setReviews(null);
      setSelectedId(null);
      setMode('home');
      toast.success('已退出选课社区');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '退出失败');
    }
  };

  const goHome = () => {
    setMode('home');
    setQuery('');
    setDepartment('');
    setSemester('');
    setList(null);
    setMine(null);
    setDetail(null);
    setSelectedId(null);
    setReviews(null);
    void loadHome();
  };

  const openMine = (tab: 'enrolled' | 'followed' | 'ignored' = 'enrolled') => {
    setMode('mine');
    setMineTab(tab);
    setList(null);
    setDetail(null);
    setSelectedId(null);
    setReviews(null);
    setMineSemester('');
    if (tab === 'enrolled') void loadEnrolled();
    else if (tab === 'followed') void loadMine(1);
    else void loadIgnored(1);
  };

  const departments = useMemo(() => filters?.departments ?? [], [filters]);
  const semesters = useMemo(() => filters?.semesters ?? [], [filters]);
  const enrolledSemesters = useMemo(
    () => [...new Set(enrollments.map((item) => item.semester).filter(Boolean))].sort().reverse(),
    [enrollments],
  );
  const visibleEnrollments = useMemo(
    () => (mineSemester ? enrollments.filter((item) => item.semester === mineSemester) : enrollments),
    [enrollments, mineSemester],
  );

  if (!state) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-xs text-ink-3">
        <Spinner /> 正在读取选课社区…
      </div>
    );
  }

  if (!state.loggedIn) {
    return (
      <div className="flex h-full items-center justify-center p-6">
        <Card className="w-full max-w-md p-6">
          <div className="mb-1 flex items-center gap-2 text-sm font-semibold text-ink">
            <BookOpen className="size-4 text-accent" /> 登录选课社区
          </div>
          <p className="mb-5 text-[0.7rem] leading-relaxed text-ink-3">
            使用 course.sjtu.plus 的社区账号登录。这里的密码是选课社区独立密码，不是 jAccount 密码；密码不会保存。
          </p>
          <div className="space-y-3">
            <Input value={email} onChange={(event) => setEmail(event.target.value)} placeholder="邮箱" autoComplete="username" />
            <Input
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void login();
              }}
              type="password"
              placeholder="选课社区密码"
              autoComplete="current-password"
            />
            <Button className="w-full" onClick={() => void login()} disabled={loginBusy}>
              {loginBusy ? <Spinner /> : null}
              {loginBusy ? '正在登录…' : '登录'}
            </Button>
          </div>
        </Card>
      </div>
    );
  }

  const homeLoading = !homeReviews;
  return (
    <div className="flex h-full flex-col">
      <PageHeader
        eyebrow="COURSE COMMUNITY"
        title="选课社区"
        description={
          state.user
            ? `已登录 ${state.user.username || state.user.email || '社区账号'} · 只读浏览`
            : '课程评价与资料浏览'
        }
        actions={
          <Button size="sm" variant="ghost" onClick={() => void logout()}>
            <LogOut className="size-3.5" /> 退出
          </Button>
        }
      />
      <div className="flex min-h-0 flex-1 gap-3 p-4 pt-0">
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <Card className="p-3">
            <div className="flex flex-wrap items-center gap-2">
              <Input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    setMode('search');
                    void loadList(1);
                  }
                }}
                placeholder="搜索课程名、课程代码或教师"
                className="min-w-[16rem] flex-1"
              />
              <select
                value={department}
                onChange={(event) => setDepartment(event.target.value)}
                className="h-9 rounded-lg border border-line bg-surface px-2 text-xs text-ink"
              >
                <option value="">全部院系</option>
                {departments.map((item) => (
                  <option key={item.name} value={item.name}>
                    {item.name}（{item.count}）
                  </option>
                ))}
              </select>
              <select
                value={semester}
                onChange={(event) => setSemester(event.target.value)}
                className="h-9 rounded-lg border border-line bg-surface px-2 text-xs text-ink"
              >
                <option value="">全部学期</option>
                {semesters.map((item) => (
                  <option key={item.name} value={item.name}>
                    {item.name}
                  </option>
                ))}
              </select>
              <Button
                size="sm"
                variant="secondary"
                onClick={() => {
                  setMode('search');
                  void loadList(1);
                }}
              >
                <Search className="size-3.5" /> 搜索
              </Button>
              {mode !== 'home' ? (
                <Button size="sm" variant="ghost" onClick={goHome}>
                  返回首页
                </Button>
              ) : null}
            </div>
          </Card>

          <div className="scroll-thin min-h-0 flex-1 overflow-y-auto pr-1">
            {mode === 'search' ? (
              loading ? (
                <div className="flex h-32 items-center justify-center gap-2 text-xs text-ink-3">
                  <Spinner /> 正在读取课程…
                </div>
              ) : list?.items?.length ? (
                <div className="space-y-2">
                  {list.items.map((course) => (
                    <CourseCard
                      key={course.id}
                      course={course}
                      active={selectedId === course.id}
                      onClick={() => void openCourse(course)}
                    />
                  ))}
                  <div className="mt-3 flex items-center justify-between text-[0.66rem] text-ink-3">
                    <span>共 {list.total} 门 · 第 {list.page} 页</span>
                    <div className="flex gap-2">
                      <Button size="sm" variant="ghost" disabled={list.page <= 1} onClick={() => void loadList(list.page - 1)}>
                        上一页
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={list.page * list.page_size >= list.total}
                        onClick={() => void loadList(list.page + 1)}
                      >
                        下一页
                      </Button>
                    </div>
                  </div>
                </div>
              ) : (
                <EmptyState icon={BookOpen} title="没有找到课程" description="换一个关键词或筛选条件试试。" />
              )
            ) : mode === 'mine' ? (
              <div className="space-y-3">
                <div className="flex flex-wrap items-center gap-2">
                  {(
                    [
                      ['enrolled', '选课记录'],
                      ['followed', '已关注'],
                      ['ignored', '已屏蔽'],
                    ] as const
                  ).map(([value, label]) => (
                    <button
                      key={value}
                      type="button"
                      onClick={() => {
                        setMineTab(value);
                        setMineSemester('');
                        setDetail(null);
                        setSelectedId(null);
                        if (value === 'enrolled') void loadEnrolled();
                        else if (value === 'followed') void loadMine(1);
                        else void loadIgnored(1);
                      }}
                      className={cn(
                        'rounded-full border px-3 py-1 text-[0.68rem] transition-colors',
                        mineTab === value
                          ? 'border-accent/50 bg-accent/10 text-accent'
                          : 'border-line bg-surface text-ink-3 hover:text-ink',
                      )}
                    >
                      {label}
                    </button>
                  ))}
                  {mineTab === 'enrolled' && enrolledSemesters.length ? (
                    <select
                      value={mineSemester}
                      onChange={(event) => setMineSemester(event.target.value)}
                      className="h-8 rounded-lg border border-line bg-surface px-2 text-[0.68rem] text-ink"
                    >
                      <option value="">全部学期</option>
                      {enrolledSemesters.map((semester) => (
                        <option key={semester} value={semester}>
                          {semester}
                        </option>
                      ))}
                    </select>
                  ) : null}
                </div>
                {mineTab === 'enrolled' ? (
                  mineLoading && !enrollments.length ? (
                    <div className="flex h-32 items-center justify-center gap-2 text-xs text-ink-3">
                      <Spinner /> 正在读取选课记录…
                    </div>
                  ) : visibleEnrollments.length ? (
                    <div className="space-y-2">
                      <div className="text-[0.64rem] text-ink-3">
                        共 {visibleEnrollments.length} 门
                      </div>
                      {visibleEnrollments.map((item) => (
                        <div key={item.id}>
                          <div className="mb-1 px-1 text-[0.62rem] text-ink-3">{item.semester}</div>
                          <CourseCard
                            course={item.course}
                            active={selectedId === item.course.id}
                            onClick={() => void openCourse(item.course)}
                          />
                        </div>
                      ))}
                    </div>
                  ) : (
                    <EmptyState
                      icon={BookOpen}
                      title="暂无选课记录"
                      description="可在 course.sjtu.plus 的“我的课程”中同步教学网选课记录。"
                    />
                  )
                ) : mineTab === 'followed' ? (
                  mineLoading && !mine ? (
                    <div className="flex h-32 items-center justify-center gap-2 text-xs text-ink-3">
                      <Spinner /> 正在读取关注课程…
                    </div>
                  ) : mine?.items?.length ? (
                    <div className="space-y-2">
                      <div className="text-[0.64rem] text-ink-3">
                        共 {mine.total} 门 · 第 {mine.page} 页
                      </div>
                      {mine.items.map((course) => (
                        <CourseCard
                          key={course.id}
                          course={course}
                          active={selectedId === course.id}
                          onClick={() => void openCourse(course)}
                        />
                      ))}
                      <div className="mt-3 flex items-center justify-end gap-2 text-[0.66rem] text-ink-3">
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={mineLoading || mine.page <= 1}
                          onClick={() => void loadMine(mine.page - 1)}
                        >
                          上一页
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={mineLoading || mine.page * mine.page_size >= mine.total}
                          onClick={() => void loadMine(mine.page + 1)}
                        >
                          下一页
                        </Button>
                      </div>
                    </div>
                  ) : (
                    <EmptyState
                      icon={BookOpen}
                      title="暂无关注课程"
                      description="在课程详情页关注课程后，会显示在这里。"
                    />
                  )
                ) : ignored?.items?.length ? (
                  <div className="space-y-2">
                    <div className="text-[0.64rem] text-ink-3">
                      共 {ignored.total} 门 · 第 {ignored.page} 页
                    </div>
                    {ignored.items.map((course) => (
                      <CourseCard
                        key={course.id}
                        course={course}
                        active={selectedId === course.id}
                        onClick={() => void openCourse(course)}
                      />
                    ))}
                    <div className="mt-3 flex items-center justify-end gap-2 text-[0.66rem] text-ink-3">
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={mineLoading || ignored.page <= 1}
                        onClick={() => void loadIgnored(ignored.page - 1)}
                      >
                        上一页
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={mineLoading || ignored.page * ignored.page_size >= ignored.total}
                        onClick={() => void loadIgnored(ignored.page + 1)}
                      >
                        下一页
                      </Button>
                    </div>
                  </div>
                ) : (
                  <EmptyState icon={BookOpen} title="暂无屏蔽课程" description="屏蔽课程后，会显示在这里。" />
                )}
              </div>
            ) : homeLoading ? (
              <div className="flex h-32 items-center justify-center gap-2 text-xs text-ink-3">
                <Spinner /> 正在读取最新点评…
              </div>
            ) : (
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <div className="text-sm font-semibold text-ink">最新点评</div>
                  <span className="text-[0.64rem] text-ink-3">共 {homeReviews?.total ?? 0} 条</span>
                </div>
                {homeReviews?.items?.length ? (
                  homeReviews.items.map((review) => (
                    <ReviewRow
                      key={review.id}
                      review={review}
                      onClick={() => {
                        if (review.course) void openCourse(review.course);
                      }}
                    />
                  ))
                ) : (
                  <EmptyState icon={BookOpen} title="暂无点评" description="社区还没有最新评价。" />
                )}
              </div>
            )}
          </div>
        </div>

        <div className="scroll-thin w-[24rem] shrink-0 overflow-y-auto rounded-xl border border-line bg-surface-2 p-4">
          {detailLoading ? (
            <CourseDetailPanel detail={detail ?? ({} as CourseDetail)} reviews={reviews} loading />
          ) : detail ? (
            <CourseDetailPanel detail={detail} reviews={reviews} loading={false} />
          ) : mode !== 'home' ? (
            <EmptyState icon={BookOpen} title="选择一门课程" description="点击左侧课程查看评分与评价。" />
          ) : (
            <div className="space-y-4">
              <div>
                <div className="mb-2 flex items-center justify-between">
                  <div className="text-sm font-semibold text-ink">热门课程</div>
                  <span className="text-[0.62rem] text-ink-3">本周</span>
                </div>
                <div className="space-y-2">
                  {hot?.items?.length ? (
                    hot.items.map((item) => (
                      <CompactCourse key={item.course.id} course={item.course} onClick={() => void openCourse(item.course)} />
                    ))
                  ) : (
                    <div className="text-[0.66rem] text-ink-3">暂无热门课程。</div>
                  )}
                </div>
              </div>
              <div className="border-t border-line pt-3">
                <div className="mb-2 flex items-center justify-between">
                  <button
                    type="button"
                    onClick={() => openMine('enrolled')}
                    className="text-sm font-semibold text-ink transition-colors hover:text-accent"
                  >
                    我的课程
                  </button>
                  <button
                    type="button"
                    onClick={() => openMine('enrolled')}
                    className="text-[0.62rem] text-ink-3 transition-colors hover:text-ink"
                  >
                    {enrollments.length ? `${enrollments.length} 门 · 查看更多` : '查看更多'}
                  </button>
                </div>
                <div className="space-y-2">
                  {enrollments.length ? (
                    enrollments.slice(0, 8).map((item) => (
                      <CompactCourse
                        key={item.id}
                        course={item.course}
                        onClick={() => void openCourse(item.course)}
                      />
                    ))
                  ) : (
                    <div className="text-[0.66rem] text-ink-3">暂无选课记录。</div>
                  )}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
