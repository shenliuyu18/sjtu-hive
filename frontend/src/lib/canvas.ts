import type { CanvasCourse } from '@/lib/models';

export function compareCanvasTerms(a: string, b: string) {
  const order = (term: string) => {
    const match = term.match(
      /(?:^|\D)(\d{4})(?:\s*-\s*(\d{4}))?\s*(?:学年|年)?[\s_]*(Fall|Spring|Summer|秋季?|春季?|夏季?)(?:学期)?\s*$/i,
    );
    if (!match) return 0;
    const start = Number(match[1]);
    const end = match[2] ? Number(match[2]) : start;
    if (match[2] && end !== start + 1) return 0;
    const season = /^(Fall|秋)/i.test(match[3]!) ? 2 : /^(Summer|夏)/i.test(match[3]!) ? 1 : 0;
    return (season === 2 ? start : end) * 3 + season;
  };
  return order(b) - order(a) || b.localeCompare(a);
}

export function latestTermOf(courses: { term: string }[]) {
  return [...new Set(courses.map((course) => course.term))]
    .sort(compareCanvasTerms)
    .find((term) => term !== '未标注学期');
}

export function groupCanvasCourses(courses: CanvasCourse[]) {
  const groups = new Map<string, CanvasCourse[]>();
  for (const course of courses) {
    const list = groups.get(course.term);
    if (list) list.push(course);
    else groups.set(course.term, [course]);
  }
  return [...groups.entries()]
    .sort(([a], [b]) => compareCanvasTerms(a, b))
    .map(([term, list]) => ({
      term,
      courses: [...list].sort((a, b) => a.name.localeCompare(b.name) || a.id - b.id),
    }));
}

export function submissionLabel(state: string | null) {
  switch (state) {
    case 'graded':
      return '已评分';
    case 'submitted':
    case 'pending_review':
      return '已提交';
    case 'unsubmitted':
      return '未提交';
    default:
      return '未知';
  }
}
