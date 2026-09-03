import { useCallback, useEffect, useMemo, useState } from 'react';
import { ChevronDown, Sparkles, Upload } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { UploadZone } from '@/components/lesson/UploadZone';
import { fetchPlaceholder } from '@/api/lesson';
import { hoursSuggestion } from '@/lib/format';
import { cn } from '@/lib/utils';
import type { LessonPlanRequest } from '@/types';

const GRADE_MAP: Record<string, string[]> = {
  小学: ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级'],
  初中: ['七年级', '八年级', '九年级'],
  高中: ['高一', '高二', '高三'],
};

const SUBJECT_MAP: Record<string, string[]> = {
  小学: ['语文', '数学', '英语', '科学', '道德与法治', '体育', '美术', '音乐'],
  初中: ['语文', '数学', '英语', '物理', '化学', '生物', '历史', '地理', '政治'],
  高中: ['语文', '数学', '英语', '物理', '化学', '生物', '历史', '地理', '政治'],
};

const GRADE_TO_NUM: Record<string, number> = {
  一年级: 1, 二年级: 2, 三年级: 3, 四年级: 4, 五年级: 5, 六年级: 6,
  七年级: 7, 八年级: 8, 九年级: 9, 高一: 10, 高二: 11, 高三: 12,
};

/** 可选章节白名单（与后端 LessonPlanRequest.OPTIONAL_SECTION_WHITELIST 保持一致） */
const OPTIONAL_SECTIONS = [
  '学情分析',
  '教学准备',
  '板书设计',
  '作业设计',
  '思政与安全教育',
  '跨学科拓展',
  '教学反思框架',
  '图示占位',
] as const;

/** 默认勾选集（与后端 OPTIONAL_SECTION_DEFAULTS 一致） */
const DEFAULT_SECTIONS: string[] = ['学情分析', '教学准备', '板书设计', '作业设计'];

const PLACEHOLDER_FALLBACK: Record<string, string> = {
  语文: '例：通过朗读和情境体验理解课文内容，掌握重点字词，体会作者情感...',
  数学: '例：掌握一元一次方程的概念和解法，能根据实际问题列方程并正确求解...',
  英语: '例：掌握本单元核心语法点，能正确使用目标语法进行书面和口头表达...',
  物理: '例：理解牛顿第一定律的内容和物理意义，能设计实验验证惯性现象...',
  化学: '例：掌握氧气的化学性质和实验室制法，能正确书写相关化学方程式...',
  生物: '例：理解细胞呼吸的过程和意义，能比较有氧呼吸与无氧呼吸的异同...',
  历史: '例：理解辛亥革命的历史背景、过程与意义，能分析其对中国近代化的影响...',
  地理: '例：理解中国地形三大阶梯的分布特征，能分析其对气候和人类活动的影响...',
  政治: '例：理解我国基本经济制度的内涵和意义，能分析现实经济现象...',
  科学: '例：了解植物的基本结构和生长特点，能动手种植并记录观察日记...',
};

const DEFAULT_PLACEHOLDER = '请输入内容！如：认识分数的概念，能读写简单分数';

interface FormValues {
  stage: string;
  grade: string;
  subject: string;
  hours: string;
  goals: string;
  mode: 'quick' | 'copilot';
  studentProfile: string;
  keyPoints: string;
  stylePreference: string;
  assignmentRequirement: string;
  specialRequirements: string;
  topic: string;
  optionalSections: string[];
}

const initialValues: FormValues = {
  stage: '',
  grade: '',
  subject: '',
  hours: '2',
  goals: '',
  mode: 'quick',
  studentProfile: '',
  keyPoints: '',
  stylePreference: '',
  assignmentRequirement: '',
  specialRequirements: '',
  topic: '',
  optionalSections: [...DEFAULT_SECTIONS],
};

interface Errors {
  stage?: string;
  grade?: string;
  subject?: string;
  hours?: string;
  goals?: string;
}

/**
 * 教案生成表单
 * B3: 课时数字输入 min=1 max=20, 非法输入红字提示, 不静默兜底
 * 级联: 学段→年级/学科; placeholder 走后端, 失败降级本地映射
 */
export function LessonForm({
  busy,
  onSubmit,
}: {
  busy: boolean;
  onSubmit: (params: LessonPlanRequest, uploadedNames: string[]) => void;
}) {
  const [values, setValues] = useState<FormValues>(initialValues);
  const [errors, setErrors] = useState<Errors>({});
  const [uploadedNames, setUploadedNames] = useState<string[]>([]);
  const [placeholder, setPlaceholder] = useState(DEFAULT_PLACEHOLDER);

  const set = <K extends keyof FormValues>(key: K, value: FormValues[K]) => {
    setValues((prev) => ({ ...prev, [key]: value }));
    setErrors((prev) => ({ ...prev, [key]: undefined }));
  };

  const onStageChange = (stage: string) => {
    set('stage', stage);
    setValues((prev) => ({ ...prev, stage, grade: '', subject: '' }));
  };

  // 学科 placeholder 动态获取
  useEffect(() => {
    if (!values.subject) {
      setPlaceholder(DEFAULT_PLACEHOLDER);
      return;
    }
    let cancelled = false;
    void fetchPlaceholder(values.subject).then((p) => {
      if (cancelled) return;
      setPlaceholder(p || PLACEHOLDER_FALLBACK[values.subject] || DEFAULT_PLACEHOLDER);
    });
    return () => {
      cancelled = true;
    };
  }, [values.subject]);

  const suggestion = useMemo(() => {
    const { stage, grade, subject } = values;
    const h = parseInt(values.hours, 10);
    if (stage && grade && subject && Number.isFinite(h) && h >= 1 && h <= 20) {
      return hoursSuggestion(stage, grade, subject, h);
    }
    return null;
  }, [values]);

  const validate = (): boolean => {
    const next: Errors = {};
    if (!values.stage) next.stage = '请选择学段';
    if (!values.grade) next.grade = '请选择年级';
    if (!values.subject) next.subject = '请选择学科';
    const h = values.hours.trim();
    if (!h) next.hours = '请输入课时（1-20）';
    else if (!/^\d+$/.test(h)) next.hours = '课时必须为数字';
    else {
      const n = parseInt(h, 10);
      if (n < 1 || n > 20) next.hours = '课时需在 1-20 之间';
    }
    if (!values.goals.trim()) next.goals = '请输入教学目标';
    else if (values.goals.trim().length < 2 || values.goals.trim().length > 500) {
      next.goals = '教学目标长度需为 2-500 字';
    }
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const submit = () => {
    if (busy) return;
    if (!validate()) {
      toast.warning('请完整填写必填字段');
      return;
    }
    const params: LessonPlanRequest = {
      stage: values.stage as LessonPlanRequest['stage'],
      grade: GRADE_TO_NUM[values.grade] || parseInt(values.grade, 10) || 1,
      subject: values.subject,
      hours: parseInt(values.hours, 10),
      goals: values.goals.trim(),
      mode: values.mode,
      Id: 'lesson_' + Date.now(),
      Question: `${values.stage}${values.grade}${values.subject}`,
      uploadedFileNames: uploadedNames.length > 0 ? uploadedNames : undefined,
    };
    if (values.studentProfile.trim()) params.studentProfile = values.studentProfile.trim();
    if (values.keyPoints.trim()) params.keyPoints = values.keyPoints.trim();
    if (values.stylePreference.trim()) params.stylePreference = values.stylePreference.trim();
    if (values.assignmentRequirement.trim()) params.assignmentRequirement = values.assignmentRequirement.trim();
    if (values.specialRequirements.trim()) params.specialRequirements = values.specialRequirements.trim();
    if (values.topic.trim()) params.topic = values.topic.trim();
    // 可选章节: 与默认集一致时不传(后端走 null=默认), 否则传当前勾选(空数组=仅骨架)
    if (
      values.optionalSections.length !== DEFAULT_SECTIONS.length ||
      values.optionalSections.some((s) => !DEFAULT_SECTIONS.includes(s))
    ) {
      params.optionalSections = [...values.optionalSections];
    }
    onSubmit(params, uploadedNames);
  };

  const toggleSection = (sec: string) => {
    setValues((prev) => ({
      ...prev,
      optionalSections: prev.optionalSections.includes(sec)
        ? prev.optionalSections.filter((s) => s !== sec)
        : [...prev.optionalSections, sec],
    }));
  };

  const FieldError = ({ msg }: { msg?: string }) =>
    msg ? <p className="mt-1 text-xs text-red-600">{msg}</p> : null;

  return (
    <div className="mx-auto max-w-3xl px-4 py-5 md:px-6">
      <div className="mb-4 flex items-center gap-2 text-lg font-bold">
        <Sparkles className="h-5 w-5 text-primary" />
        教案生成
      </div>

      {/* 基础信息 */}
      <section className="mb-5 rounded-xl border bg-card p-5 shadow-sm">
        <h3 className="mb-3 text-xs font-bold uppercase tracking-wide text-muted-foreground">基础信息</h3>
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3">
          <div>
            <Label className="mb-1.5 block text-xs">
              学段 <span className="text-red-500">*</span>
            </Label>
            <Select value={values.stage || undefined} onValueChange={onStageChange}>
              <SelectTrigger className={cn(errors.stage && 'border-red-400')}>
                <SelectValue placeholder="请选择" />
              </SelectTrigger>
              <SelectContent>
                {Object.keys(GRADE_MAP).map((s) => (
                  <SelectItem key={s} value={s}>
                    {s}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <FieldError msg={errors.stage} />
          </div>
          <div>
            <Label className="mb-1.5 block text-xs">
              年级 <span className="text-red-500">*</span>
            </Label>
            <Select value={values.grade || undefined} onValueChange={(v) => set('grade', v)} disabled={!values.stage}>
              <SelectTrigger className={cn(errors.grade && 'border-red-400')}>
                <SelectValue placeholder={values.stage ? '请选择年级' : '请先选择学段'} />
              </SelectTrigger>
              <SelectContent>
                {(GRADE_MAP[values.stage] || []).map((g) => (
                  <SelectItem key={g} value={g}>
                    {g}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <FieldError msg={errors.grade} />
          </div>
          <div>
            <Label className="mb-1.5 block text-xs">
              学科 <span className="text-red-500">*</span>
            </Label>
            <Select value={values.subject || undefined} onValueChange={(v) => set('subject', v)} disabled={!values.stage}>
              <SelectTrigger className={cn(errors.subject && 'border-red-400')}>
                <SelectValue placeholder={values.stage ? '请选择学科' : '请先选择学段'} />
              </SelectTrigger>
              <SelectContent>
                {(SUBJECT_MAP[values.stage] || []).map((s) => (
                  <SelectItem key={s} value={s}>
                    {s}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <FieldError msg={errors.subject} />
          </div>
        </div>

        <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-2">
          <div>
            <Label className="mb-1.5 block text-xs">
              课时 <span className="text-red-500">*</span>
            </Label>
            <Input
              type="number"
              min={1}
              max={20}
              value={values.hours}
              onChange={(e) => set('hours', e.target.value)}
              aria-label="课时数（1-20）"
              className={cn(errors.hours && 'border-red-400 focus:ring-red-200')}
            />
            {suggestion ? (
              <p className="mt-1 text-xs text-muted-foreground">建议：{suggestion}</p>
            ) : (
              <FieldError msg={errors.hours} />
            )}
            {!errors.hours && !suggestion && (
              <p className="mt-1 text-xs text-muted-foreground">请输入 1-20 的数字</p>
            )}
          </div>
          <div>
            <Label className="mb-1.5 block text-xs">教学模式</Label>
            <Select value={values.mode} onValueChange={(v) => set('mode', v as 'quick' | 'copilot')}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="quick">快速生成</SelectItem>
                <SelectItem value="copilot">分步确认</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      </section>

      {/* 教案结构与内容 (2026-09-02 教案结构改造) */}
      <section className="mb-5 rounded-xl border bg-card p-5 shadow-sm">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-xs font-bold uppercase tracking-wide text-muted-foreground">
            教案内容
          </h3>
        </div>
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div>
            <Label className="mb-1.5 block text-xs">
              课题名
              <span className="ml-1 font-normal text-muted-foreground/70">选填 · 会显示在教案头部</span>
            </Label>
            <Input
              value={values.topic}
              placeholder="如：两位数乘一位数（不进位）"
              onChange={(e) => set('topic', e.target.value)}
              className="text-xs"
            />
          </div>
        </div>
        <div className="mt-4">
          <div className="flex items-center justify-between">
            <Label className="text-xs">
              附加章节
              <span className="ml-2 font-normal text-muted-foreground/70">
                固定包含：教学目标 / 教学重难点 / 教学过程
              </span>
            </Label>
            <button
              type="button"
              className="text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
              onClick={() => setValues((prev) => ({ ...prev, optionalSections: [...DEFAULT_SECTIONS] }))}
            >
              恢复默认
            </button>
          </div>
          <div className="mt-2 grid grid-cols-2 gap-2 md:grid-cols-4">
            {OPTIONAL_SECTIONS.map((sec) => {
              const checked = values.optionalSections.includes(sec);
              return (
                <label
                  key={sec}
                  className={
                    'flex cursor-pointer items-center gap-2 rounded-md border px-2.5 py-2 text-xs transition-colors ' +
                    (checked
                      ? 'border-primary/60 bg-primary/5 text-foreground'
                      : 'border-border text-muted-foreground hover:bg-muted/50')
                  }
                >
                  <input
                    type="checkbox"
                    className="h-3.5 w-3.5 accent-primary"
                    checked={checked}
                    onChange={() => toggleSection(sec)}
                  />
                  {sec}
                </label>
              );
            })}
          </div>
          {values.optionalSections.length === 0 && (
            <p className="mt-2 text-xs text-muted-foreground">
              已选择仅骨架模式（不含任何附加章节）
            </p>
          )}
        </div>
      </section>

      {/* 教学目标 */}
      <section className="mb-5 rounded-xl border bg-card p-5 shadow-sm">
        <h3 className="mb-3 text-xs font-bold uppercase tracking-wide text-muted-foreground">教学目标</h3>
        <Label className="mb-1.5 block text-xs">
          教学目标 <span className="text-red-500">*</span>
        </Label>
        <Textarea
          rows={3}
          value={values.goals}
          placeholder={placeholder}
          onChange={(e) => set('goals', e.target.value)}
          className={cn(errors.goals && 'border-red-400')}
        />
        <div className="mt-1 flex items-center justify-between">
          <FieldError msg={errors.goals} />
          <span className="ml-auto text-[11px] text-muted-foreground">
            {values.goals.trim().length}/500
          </span>
        </div>
      </section>

      {/* 个性化上下文 */}
      <section className="mb-5 rounded-xl border bg-card p-5 shadow-sm">
        <h3 className="mb-1 text-xs font-bold uppercase tracking-wide text-muted-foreground">
          个性化上下文
          <span className="ml-2 font-normal normal-case text-muted-foreground/70">选填 · 会记忆并在下次生成时复用</span>
        </h3>
        <div className="mt-3 grid grid-cols-1 gap-4">
          {(
            [
              ['studentProfile', '学情分析', '如：班级计算能力偏弱，基础参差不齐，喜欢小组竞赛'],
              ['keyPoints', '教学重难点', '如：重点=分数意义理解，难点=分数与除法的关系（留空则自动从课标推导）'],
              ['stylePreference', '教学风格偏好', '如：讲练结合，边做题边学习，多用生活实例导入'],
              ['assignmentRequirement', '作业/评价要求', '如：作业量控制在20分钟内，分基础题和拓展题两层'],
              ['specialRequirements', '特殊要求', '其他需求，如：需要包含课堂游戏环节'],
            ] as const
          ).map(([key, label, ph]) => (
            <div key={key}>
              <Label className="mb-1.5 block text-xs">{label}</Label>
              <Textarea
                rows={2}
                value={values[key]}
                placeholder={ph}
                onChange={(e) => set(key, e.target.value)}
                className="text-xs"
              />
            </div>
          ))}
        </div>
      </section>

      {/* 参考资料 */}
      <section className="mb-5 rounded-xl border bg-card p-5 shadow-sm">
        <h3 className="mb-3 flex items-center gap-1.5 text-xs font-bold uppercase tracking-wide text-muted-foreground">
          <Upload className="h-3.5 w-3.5" />
          参考资料
          <span className="font-normal normal-case text-muted-foreground/70">选填 · 教案模式专属</span>
        </h3>
        <UploadZone disabled={busy} onUploaded={setUploadedNames} />
      </section>

      {/* 生成按钮 */}
      <div className="flex justify-end">
        <Button size="lg" onClick={submit} disabled={busy} className="px-8">
          {busy ? '生成中...' : '生成教案'}
        </Button>
      </div>
    </div>
  );
}
