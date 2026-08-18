/**
 * GagneFlow Frontend v2.0 — 教案生成 + 智能对话
 *
 * 模块拆分：
 *   1. 智能对话模块 (moduleChat) — 轻量级聊天，自由问答
 *   2. 教案生成模块 (moduleLesson) — 结构化教案生成，模板/参数/结果/导出
 */

class GagneFlowApp {
    constructor() {
        // API 基础地址（自动适配部署域名）
        this.apiBase = window.location.origin + '/api';
        this.authBase = window.location.origin + '/api/auth';

        // 会话状态
        this.sessionId = this.genId();
        this.module = 'chat';        // 'chat' | 'lesson'
        this.isStreaming = false;
        this.currentHistory = [];
        this.uploadedFiles = [];

        // 认证状态
        this.token = localStorage.getItem('token');
        this.refreshToken = localStorage.getItem('refreshToken');
        this.username = localStorage.getItem('username');
        this.chatHistories = JSON.parse(localStorage.getItem('chatHistories') || '[]');

        // 教案数据配置（级联选择）
        this.gradeMap = {
            '小学': ['一年级','二年级','三年级','四年级','五年级','六年级'],
            '初中': ['七年级','八年级','九年级'],
            '高中': ['高一','高二','高三']
        };
        this.subjectMap = {
            '小学': ['语文','数学','英语','科学','道德与法治','体育','美术','音乐'],
            '初中': ['语文','数学','英语','物理','化学','生物','历史','地理','政治'],
            '高中': ['语文','数学','英语','物理','化学','生物','历史','地理','政治']
        };
        // 课时智能补全建议
        this.hoursSuggestions = [
            '1课时','2课时','3课时','4课时',
            '第一课时：导入与概念讲解',
            '第二课时：实践与练习',
            '第三课时：总结与评估'
        ];

        this.cacheDom();
        this.bindAuthSwitch();
        this.bindAuth();
        this.bindModule();
        this.bindChat();
        this.bindLesson();
        this.checkAuth();
        this.renderHistory();
        hljs.configure({ languages: [] });
    }

    genId() { return 'sess_' + Math.random().toString(36).slice(2, 9) + '_' + Date.now(); }

    // ===== DOM 缓存 =====
    cacheDom() {
        this.authOverlay = document.getElementById('authOverlay');
        this.authContainer = document.querySelector('.auth-container');
        this.appLayout = document.getElementById('appLayout');

        // 模块
        this.moduleChat = document.getElementById('moduleChat');
        this.moduleLesson = document.getElementById('moduleLesson');
        this.navChatBtn = document.getElementById('navChatBtn');
        this.navLessonBtn = document.getElementById('navLessonBtn');
        this.newChatBtn = document.getElementById('newChatBtn');

        // 对话
        this.chatMessages = document.getElementById('chatMessages');
        this.messageInput = document.getElementById('messageInput');
        this.sendButton = document.getElementById('sendButton');
        this.chatHistoryList = document.getElementById('chatHistoryList');
        this.uploadDropZone = document.getElementById('uploadDropZone');
        this.uploadFileList = document.getElementById('uploadFileList');
        this.uploadFileInput = document.getElementById('uploadFileInput');
        this.browseFilesBtn = document.getElementById('browseFilesBtn');
        this.headerTitle = document.getElementById('headerTitle');
        this.welcome = document.getElementById('welcomeGreeting');

        // 教案
        this.lessonPanel = document.getElementById('lessonPanel');
        this.lpStage = document.getElementById('lpStage');
        this.lpGrade = document.getElementById('lpGrade');
        this.lpSubject = document.getElementById('lpSubject');
        this.lpHours = document.getElementById('lpHours');
        this.lpGoals = document.getElementById('lpGoals');
        this.lpMode = document.getElementById('lpMode');
        this.lpStudentProfile = document.getElementById('lpStudentProfile');
        this.lpKeyPoints = document.getElementById('lpKeyPoints');
        this.lpStylePreference = document.getElementById('lpStylePreference');
        this.lpAssignment = document.getElementById('lpAssignment');
        this.lpSpecial = document.getElementById('lpSpecial');
        this.lpToggleUpload = document.getElementById('lpToggleUpload');
        this.btnGenerate = document.getElementById('btnGenerate');
        this.lessonResult = document.getElementById('lessonResult');
        this.lessonMessages = document.getElementById('lessonMessages');
        // 进度弹窗
        this.progressModal = document.getElementById('progressModal');
        this.pmBody = document.getElementById('pmBody');
        this.pmFooter = document.getElementById('pmFooter');
        this.pmClose = document.getElementById('pmClose');
        this.pmViewResult = document.getElementById('pmViewResult');
        this.pmCloseResult = document.getElementById('pmCloseResult');

        // 工作台
        this.workbench = document.getElementById('workbench');
        this.wbOutline = document.getElementById('wbOutline');
        this.wbContent = document.getElementById('wbContent');
    }

    // ===== 认证 =====
    bindAuthSwitch() {
        document.getElementById('toSignupBtn').addEventListener('click', () => {
            this.authContainer.classList.add('signup');
        });
        document.getElementById('toSigninBtn').addEventListener('click', () => {
            this.authContainer.classList.remove('signup');
        });
    }

    checkAuth() {
        if (this.token) { this.showApp(); this.loadServerHistory(); } else { this.showAuth(); }
    }
    showAuth() { this.authOverlay.style.display = 'flex'; this.appLayout.style.display = 'none'; }
    showApp() {
        this.authOverlay.style.display = 'none';
        this.appLayout.style.display = 'flex';
        document.getElementById('sidebarUsername').textContent = this.username || '';
        // 初始化模块状态 — 默认进入"智能对话"，显示"新建对话"按钮
        this.setModule('chat');
    }

    bindAuth() {
        document.getElementById('signinForm').addEventListener('submit', async (e) => {
            e.preventDefault();
            const u = document.getElementById('signinUsername').value.trim();
            const p = document.getElementById('signinPassword').value;
            const err = document.getElementById('signinError');
            if (!u || !p) { err.textContent = '请填写用户名和密码'; return; }
            err.textContent = '';
            try {
                const res = await fetch(this.authBase + '/login', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: u, password: p })
                });
                const data = await res.json();
                if (!res.ok) { err.textContent = data.error || '登录失败'; return; }
                this.saveAuth(data);
            } catch (e) {
                err.textContent = '网络连接失败，请检查网络后重试';
                console.error('Login error:', e);
            }
        });

        document.getElementById('signupForm').addEventListener('submit', async (e) => {
            e.preventDefault();
            const u = document.getElementById('signupUsername').value.trim();
            const p = document.getElementById('signupPassword').value;
            const err = document.getElementById('signupError');
            if (!u || !p) { err.textContent = '请填写用户名和密码'; return; }
            if (p.length < 6) { err.textContent = '密码至少6位'; return; }
            err.textContent = '';
            try {
                const res = await fetch(this.authBase + '/register', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: u, password: p })
                });
                const data = await res.json();
                if (!res.ok) { err.textContent = data.error || '注册失败'; return; }
                this.showToast('注册成功，请登录', 'success');
                this.authContainer.classList.remove('signup');
                document.getElementById('signinUsername').value = u;
            } catch (e) {
                err.textContent = '网络连接失败，请检查网络后重试';
                console.error('Register error:', e);
            }
        });

        document.getElementById('logoutBtn').addEventListener('click', () => this.logout());
    }

    saveAuth(data) {
        this.token = data.token; this.refreshToken = data.refreshToken; this.username = data.username;
        localStorage.setItem('token', this.token); localStorage.setItem('refreshToken', this.refreshToken);
        localStorage.setItem('username', this.username);
        this.showApp(); this.loadServerHistory();
    }

    logout() { localStorage.clear(); this.token = null; this.refreshToken = null; this.username = null; this.showAuth(); }

    // ===== API 请求 =====
    async apiFetch(url, opts = {}) {
        const headers = { ...opts.headers };
        if (this.token) headers['Authorization'] = 'Bearer ' + this.token;

        // 流式请求（SSE）不设超时；普通请求默认 30s 超时
        const noTimeout = opts.timeout === false;
        delete opts.timeout;
        const signal = noTimeout
            ? (opts.signal || null)
            : (opts.signal
                ? (AbortSignal.any ? AbortSignal.any([opts.signal, AbortSignal.timeout(30000)]) : opts.signal)
                : AbortSignal.timeout(30000));

        let res;
        try {
            res = await fetch(url, { ...opts, headers, ...(signal ? { signal } : {}) });
        } catch (e) {
            const name = (e.name || '').toLowerCase();
            const msg  = (e.message || '').toLowerCase();

            // 用户主动取消 — 直接抛出原始 AbortError
            if (opts.signal && opts.signal.aborted && name === 'aborterror') {
                throw e;
            }
            // 超时
            if (name === 'timeouterror' || name === 'aborterror') {
                throw new Error('请求超时，请确认后端服务是否已启动');
            }
            // 网络不可达
            if (name === 'typeerror' || msg.includes('network') || msg.includes('failed to fetch')
                || msg.includes('fetch') || msg.includes('cors')) {
                throw new Error('网络连接失败，请检查网络后重试');
            }
            throw new Error('网络请求异常: ' + (e.message || '未知错误'));
        }

        // 401/403: Token 过期或无效 — 尝试刷新
        if ((res.status === 401 || res.status === 403) && this.refreshToken) {
            const ok = await this.tryRefresh();
            if (ok) { headers['Authorization'] = 'Bearer ' + this.token; res = await fetch(url, { ...opts, headers }); }
            else { this.logout(); throw new Error('登录已过期，请重新登录'); }
        }
        return res;
    }

    async tryRefresh() {
        try {
            const res = await fetch(this.authBase + '/refresh', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ refreshToken: this.refreshToken })
            });
            if (!res.ok) return false;
            const d = await res.json(); this.token = d.token;
            localStorage.setItem('token', this.token); return true;
        } catch { return false; }
    }

    // ===== 模块切换 =====
    bindModule() {
        this.navChatBtn.addEventListener('click', () => this.setModule('chat'));
        this.navLessonBtn.addEventListener('click', () => this.setModule('lesson'));
    }

    setModule(m) {
        if (this.isStreaming) return;
        this.module = m;
        this.navChatBtn.classList.toggle('active', m === 'chat');
        this.navLessonBtn.classList.toggle('active', m === 'lesson');
        this.moduleChat.classList.toggle('active', m === 'chat');
        this.moduleLesson.classList.toggle('active', m === 'lesson');
        this.newChatBtn.style.display = m === 'chat' ? 'flex' : 'none';
        if (m === 'lesson') this.initLessonCascade();
    }

    // ===== 对话模块 =====
    bindChat() {
        this.newChatBtn.addEventListener('click', () => this.newChat());
        this.sendButton.addEventListener('click', () => this.send());
        this.messageInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this.send(); }
        });
        this.messageInput.addEventListener('input', () => {
            this.messageInput.style.height = 'auto';
            this.messageInput.style.height = Math.min(this.messageInput.scrollHeight, 120) + 'px';
        });
    }

    async send() {
        const msg = this.messageInput.value.trim();
        if (!msg || this.isStreaming) return;
        this.messageInput.value = ''; this.messageInput.style.height = 'auto';
        if (this.welcome) this.welcome.style.display = 'none';
        this.addMessage('user', msg);
        this.isStreaming = true; this.sendButton.disabled = true;
        try {
            await this.streamMessage('/chat_stream', msg);
        } catch (err) {
            this.addMessage('assistant', '发送失败: ' + err.message);
            this.showToast(err.message, 'error');
        } finally {
            this.isStreaming = false; this.sendButton.disabled = false; this.registerCurrentSession();
        }
    }

    async uploadAll() {
        for (const f of this.uploadedFiles) {
            const fd = new FormData(); fd.append('file', f);
            await this.apiFetch(this.apiBase + '/upload', { method: 'POST', body: fd });
        }
        this.uploadedFiles = []; this.renderUploadFiles();
        this.showToast('文件上传完成', 'success');
    }

    // ===== 增强版文件上传 =====
    addFiles(files) {
        if (!files || !files.length) return;
        const allowed = ['txt','md','pdf','docx'];
        for (const f of files) {
            if (!allowed.includes(f.name.split('.').pop().toLowerCase())) {
                this.showToast(f.name + ' 格式不支持', 'error'); continue;
            }
            if (!this.uploadedFiles.find(u => u.name === f.name)) {
                this.uploadedFiles.push(f);
            }
        }
        this.uploadFileInput.value = '';
        this.renderFileCards();
        // 自动上传
        this.uploadAllWithProgress();
    }

    renderFileCards() {
        this.uploadFileList.innerHTML = this.uploadedFiles.map((f, i) => {
            const ext = f.name.split('.').pop().toLowerCase();
            const iconClass = ext === 'pdf' ? 'file-icon-pdf' : ext === 'docx' ? 'file-icon-docx'
                : ext === 'md' ? 'file-icon-md' : 'file-icon-txt';
            const iconSvg = {
                pdf: '<path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/>',
                docx:'<path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><line x1="10" y1="9" x2="8" y2="9"/>',
                txt: '<path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>',
                md: '<path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="8" y1="13" x2="16" y2="13"/><line x1="8" y1="17" x2="16" y2="17"/><line x1="10" y1="9" x2="8" y2="9"/>'
            }[ext] || iconSvg.txt;
            const size = f.size > 1024*1024 ? (f.size/(1024*1024)).toFixed(1)+'MB' : (f.size/1024).toFixed(0)+'KB';
            const status = f._error ? 'error' : f._done ? 'done' : '';
            return `<div class="uf-item ${status}" id="uf-item-${i}">
                <div class="uf-icon ${iconClass}">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">${iconSvg}</svg>
                </div>
                <div class="uf-info">
                    <div class="uf-name">${f.name}</div>
                    <div class="uf-meta">${size}</div>
                    <div class="uf-progress"><div class="uf-progress-bar" id="uf-progress-${i}"></div></div>
                </div>
                <div class="uf-actions">
                    <button class="uf-btn uf-btn-preview" data-idx="${i}" title="预览">预览</button>
                    <button class="uf-btn uf-btn-remove" data-idx="${i}" title="移除">&times;</button>
                </div>
            </div>`;
        }).join('');
        this.uploadFileList.querySelectorAll('.uf-btn-remove').forEach(btn => {
            btn.addEventListener('click', () => { this.uploadedFiles.splice(+btn.dataset.idx,1); this.renderFileCards(); });
        });
        this.uploadFileList.querySelectorAll('.uf-btn-preview').forEach(btn => {
            btn.addEventListener('click', () => this.previewFile(+btn.dataset.idx));
        });
    }

    async uploadAllWithProgress() {
        for (let i = 0; i < this.uploadedFiles.length; i++) {
            const f = this.uploadedFiles[i];
            if (f._done || f._uploading) continue;
            f._uploading = true;
            const bar = document.getElementById('uf-progress-' + i);
            if (bar) bar.style.width = '30%';
            try {
                const fd = new FormData(); fd.append('file', f);
                const res = await this.apiFetch(this.apiBase + '/upload', { method: 'POST', body: fd });
                if (res.ok) {
                    if (bar) bar.style.width = '100%';
                    f._done = true; f._error = null;
                    const item = document.getElementById('uf-item-' + i);
                    if (item) item.classList.add('done');
                } else {
                    f._error = '上传失败: ' + res.status;
                    const item = document.getElementById('uf-item-' + i);
                    if (item) item.classList.add('error');
                }
            } catch (e) {
                f._error = e.message;
                const item = document.getElementById('uf-item-' + i);
                if (item) item.classList.add('error');
            }
            f._uploading = false;
        }
        this.uploadedFiles = this.uploadedFiles.filter(f => !f._error);
        this.renderFileCards();
    }

    async previewFile(idx) {
        const f = this.uploadedFiles[idx];
        if (!f) return;
        const ext = f.name.split('.').pop().toLowerCase();
        if (!['txt','md'].includes(ext)) {
            this.showToast('仅支持预览 txt/md 文件内容', 'error'); return;
        }
        // 查找已存在的预览面板
        let panel = document.getElementById('uf-preview-' + idx);
        if (panel) { panel.classList.toggle('show'); return; }
        const item = document.getElementById('uf-item-' + idx);
        panel = document.createElement('div');
        panel.className = 'uf-preview show'; panel.id = 'uf-preview-' + idx;
        panel.textContent = '加载中...';
        item.after(panel);
        const reader = new FileReader();
        reader.onload = () => { panel.textContent = reader.result; };
        reader.readAsText(f);
    }

    // 旧方法兼容
    handleFiles(files) { this.addFiles(files); }
    renderUploadFiles() { this.renderFileCards(); }
    async uploadAll() { await this.uploadAllWithProgress(); }

    async streamMessage(endpoint, msg) {
        const assistantEl = this.addMessage('assistant', '', true);
        const bubble = assistantEl.querySelector('.message-bubble');
        const res = await this.apiFetch(this.apiBase + endpoint, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ Id: this.sessionId, Question: msg }),
            timeout: false
        });
        if (!res.ok) throw new Error('服务器返回 ' + res.status + '，请稍后重试');

        let reader, decoder;
        try {
            if (!res.body) throw new Error('响应流不可用');
            reader = res.body.getReader();
            decoder = new TextDecoder();
        } catch (e) {
            throw new Error('无法读取响应流，请稍后重试');
        }

        let buf = '', full = '', hasContent = false;
        try {
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                hasContent = true;
                buf += decoder.decode(value, { stream: true });
                const lines = buf.split('\n'); buf = lines.pop() || '';
                for (const line of lines) {
                    if (!line.startsWith('data:')) continue;
                    const raw = line.slice(5).trim();
                    if (!raw || raw === '[DONE]') continue;
                    try {
                        const m = JSON.parse(raw);
                        if (m.type === 'content') {
                            full += m.data || '';
                            bubble.innerHTML = this.renderMd(full); this.highlightCode(bubble); this.scrollDown();
                        } else if (m.type === 'error') {
                            bubble.innerHTML = this.renderMd('[错误] ' + (m.data || ''));
                            this.currentHistory.push({ role: 'user', content: msg });
                            this.currentHistory.push({ role: 'assistant', content: '[错误] ' + (m.data || '') });
                            return;
                        } else if (m.type === 'done') break;
                    } catch {}
                }
            }
        } catch (e) {
            // SSE 流中断 — 如已收到部分内容则使用它，否则抛出
            if (hasContent && full) {
                bubble.innerHTML = this.renderMd(full); this.highlightCode(bubble); this.scrollDown();
                this.currentHistory.push({ role: 'user', content: msg });
                this.currentHistory.push({ role: 'assistant', content: full + '\n\n*(连接中断，内容可能不完整)*' });
                this.showToast('连接中断，已显示部分内容', 'error');
                return;
            }
            throw new Error('连接中断，请稍后重试');
        }
        if (!full) full = '(无响应内容)';
        bubble.innerHTML = this.renderMd(full); this.highlightCode(bubble); this.scrollDown();
        this.currentHistory.push({ role: 'user', content: msg });
        this.currentHistory.push({ role: 'assistant', content: full });
    }

    addMessage(role, content, streaming) {
        const div = document.createElement('div'); div.className = 'message ' + role;
        const av = document.createElement('div'); av.className = 'message-avatar';
        av.textContent = role === 'user' ? (this.username || '?').charAt(0).toUpperCase() : '';
        if (role === 'assistant') av.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 016.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z"/></svg>';
        const body = document.createElement('div'); body.className = 'message-body';
        const bubble = document.createElement('div'); bubble.className = 'message-bubble';
        if (streaming) bubble.innerHTML = '<div class="typing-dots"><span></span><span></span><span></span></div>';
        else { bubble.innerHTML = this.renderMd(content); this.highlightCode(bubble); }
        body.appendChild(bubble); div.appendChild(av); div.appendChild(body);
        this.chatMessages.appendChild(div); this.scrollDown();
        return div;
    }

    scrollDown() { this.chatMessages.scrollTop = this.chatMessages.scrollHeight; }

    renderMd(text) {
        if (!text) return '';
        if (typeof marked !== 'undefined') { marked.setOptions({ breaks: true, gfm: true }); return marked.parse(text); }
        return text.replace(/\n/g, '<br>');
    }
    highlightCode(el) { el.querySelectorAll('pre code').forEach(b => { try { hljs.highlightElement(b); } catch {} }); }

    // ===== 历史会话 =====
    async loadServerHistory() {
        try {
            const res = await this.apiFetch(this.apiBase + '/chat/history');
            if (!res.ok) return;
            const data = await res.json();
            this.chatHistories = data.map(h => ({ id: h.sessionId, title: h.title || '新对话', time: h.time || Date.now() }));
            this.renderHistory();
        } catch {}
    }

    async registerCurrentSession() {
        if (!this.currentHistory.length || !this.token) return;
        const title = (this.currentHistory[0]?.content || '新对话').slice(0, 30);
        try {
            await this.apiFetch(this.apiBase + '/chat/history/register', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: this.sessionId, title })
            });
        } catch {}
        const existing = this.chatHistories.find(h => h.id === this.sessionId);
        if (existing) { existing.title = title; existing.time = Date.now(); }
        else { this.chatHistories.unshift({ id: this.sessionId, title, time: Date.now() }); }
        localStorage.setItem('chatHistories', JSON.stringify(this.chatHistories.slice(0, 50)));
        this.renderHistory();
    }

    newChat() {
        // 已在新会话中（无历史消息 + 欢迎页存在）
        if (this.currentHistory.length === 0 && this.welcome) {
            this.showToast('您已经在新会话中了', 'error');
            return;
        }
        this.registerCurrentSession();
        this.sessionId = this.genId(); this.currentHistory = [];
        this.chatMessages.innerHTML = '';
        const w = document.createElement('div'); w.className = 'welcome'; w.id = 'welcomeGreeting';
        w.innerHTML = '<svg width="48" height="48" viewBox="0 0 64 64" class="welcome-icon"><rect width="64" height="64" rx="14" fill="#f5f0eb"/><path d="M32 14 L20 20 V44 L32 38 L44 44 V20 Z" fill="none" stroke="#c48650" stroke-width="2" stroke-linejoin="round"/><line x1="32" y1="14" x2="32" y2="38" stroke="#c48650" stroke-width="1.5"/></svg><h2>GagneFlow</h2><p>输入教学需求，开始生成教案</p>';
        this.chatMessages.appendChild(w); this.welcome = w;
        this.headerTitle.textContent = '新对话';
    }

    renderHistory() {
        if (!this.chatHistoryList) return;
        this.chatHistoryList.innerHTML = this.chatHistories.map(h =>
            `<div class="history-item${h.id === this.sessionId ? ' active' : ''}" data-id="${h.id}">
                <span class="history-item-title">${h.title}</span>
                <span class="history-item-del" data-id="${h.id}">&times;</span>
            </div>`).join('');
        this.chatHistoryList.querySelectorAll('.history-item').forEach(el => {
            el.addEventListener('click', (e) => {
                if (e.target.classList.contains('history-item-del')) {
                    const id = e.target.dataset.id;
                    this.chatHistories = this.chatHistories.filter(h => h.id !== id);
                    localStorage.setItem('chatHistories', JSON.stringify(this.chatHistories));
                    if (id === this.sessionId) this.newChat();
                    this.renderHistory(); return;
                }
                const id = el.dataset.id;
                if (id === this.sessionId) return;
                this.registerCurrentSession();
                const h = this.chatHistories.find(h => h.id === id);
                if (!h) return;
                this.sessionId = h.id; this.currentHistory = [];
                this.chatMessages.innerHTML = ''; this.welcome = null;
                this.headerTitle.textContent = h.title;
                this.apiFetch(this.apiBase + '/chat/messages/' + id).then(r => r.json()).then(data => {
                    data.forEach(m => {
                        this.addMessage(m.role, m.content);
                        this.currentHistory.push({ role: m.role, content: m.content });
                    });
                    this.renderHistory();
                }).catch(() => {});
            });
        });
    }

    // ===== 教案模块 =====
    bindLesson() {
        // 级联选择：学段 → 年级 + 学科
        this.lpStage.addEventListener('change', () => this.onStageChange());
        // 学科选择 → 更新 placeholder
        this.lpSubject.addEventListener('change', () => this.updateGoalsPlaceholder());
        // 课时自动补全
        this.lpHours.addEventListener('focus', () => this.buildHoursSuggestions());
        // 生成按钮
        this.btnGenerate.addEventListener('click', () => this.generateLessonPlan());
        // 必填字段实时校验
        [this.lpStage, this.lpGrade, this.lpSubject, this.lpHours, this.lpGoals].forEach(el => {
            el.addEventListener('blur', () => this.validateField(el));
            el.addEventListener('input', () => this.clearFieldError(el));
        });
        // 进度弹窗事件
        this.pmCloseResult.addEventListener('click', () => this.hideProgressModal());
        this.pmClose.addEventListener('click', () => this.hideProgressModal());
        // 文件上传（教案生成模式专属）— 拖拽 + 点击选择 + 折叠切换
        this.lpToggleUpload.addEventListener('click', () => this.toggleUploadPanel());
        this.browseFilesBtn.addEventListener('click', (e) => { e.preventDefault(); this.uploadFileInput.click(); });
        this.uploadDropZone.querySelector('.drop-zone-inner').addEventListener('click', () => this.uploadFileInput.click());
        this.uploadFileInput.addEventListener('change', (e) => this.addFiles(e.target.files));
        ['dragenter','dragover','dragleave','drop'].forEach(ev => {
            this.uploadDropZone.addEventListener(ev, (e) => { e.preventDefault(); e.stopPropagation(); });
        });
        this.uploadDropZone.addEventListener('dragenter', () => this.uploadDropZone.classList.add('dragover'));
        this.uploadDropZone.addEventListener('dragover', () => this.uploadDropZone.classList.add('dragover'));
        this.uploadDropZone.addEventListener('dragleave', () => this.uploadDropZone.classList.remove('dragover'));
        this.uploadDropZone.addEventListener('drop', (e) => {
            this.uploadDropZone.classList.remove('dragover');
            this.addFiles(e.dataTransfer.files);
        });
    }

    toggleUploadPanel() {
        const wrap = document.getElementById('lpUploadWrap');
        wrap.classList.toggle('collapsed');
        this.lpToggleUpload.classList.toggle('collapsed');
    }

    initLessonCascade() {
        if (!this.lpStage.value) return;
        this.onStageChange();
    }

    onStageChange() {
        const stage = this.lpStage.value;
        const grades = this.gradeMap[stage] || [];
        const subjects = this.subjectMap[stage] || [];

        this.lpGrade.innerHTML = '<option value="">请选择年级</option>';
        grades.forEach(g => { const opt = document.createElement('option'); opt.value = g; opt.textContent = g; this.lpGrade.appendChild(opt); });
        this.lpGrade.disabled = !stage;

        this.lpSubject.innerHTML = '<option value="">请选择学科</option>';
        subjects.forEach(s => { const opt = document.createElement('option'); opt.value = s; opt.textContent = s; this.lpSubject.appendChild(opt); });
        this.lpSubject.disabled = !stage;

        // 学科变化时更新教学目标 placeholder
        this.updateGoalsPlaceholder();

        this.buildHoursSuggestions();
    }

    // 学科 → placeholder 映射（优先从后端 API 动态获取）
    updateGoalsPlaceholder() {
        const subject = this.lpSubject.value;
        if (!subject) {
            this.lpGoals.placeholder = '请输入内容！如：认识分数的概念，能读写简单分数';
            return;
        }
        // 先从 API 获取学科专属 placeholder
        this.apiFetch(this.apiBase + '/lesson_plan/placeholder/' + encodeURIComponent(subject))
            .then(r => r.json())
            .then(data => {
                if (data.placeholder) {
                    this.lpGoals.placeholder = data.placeholder;
                }
            })
            .catch(() => {
                // 降级为本地映射
                const fallback = {
                    '语文':'例：通过朗读和情境体验理解课文内容，掌握重点字词，体会作者情感...',
                    '数学':'例：掌握一元一次方程的概念和解法，能根据实际问题列方程并正确求解...',
                    '英语':'例：掌握本单元核心语法点，能正确使用目标语法进行书面和口头表达...',
                    '物理':'例：理解牛顿第一定律的内容和物理意义，能设计实验验证惯性现象...',
                    '化学':'例：掌握氧气的化学性质和实验室制法，能正确书写相关化学方程式...',
                    '生物':'例：理解细胞呼吸的过程和意义，能比较有氧呼吸与无氧呼吸的异同...',
                    '历史':'例：理解辛亥革命的历史背景、过程与意义，能分析其对中国近代化的影响...',
                    '地理':'例：理解中国地形三大阶梯的分布特征，能分析其对气候和人类活动的影响...',
                    '政治':'例：理解我国基本经济制度的内涵和意义，能分析现实经济现象...',
                    '科学':'例：了解植物的基本结构和生长条件，能动手种植并记录观察日记...'
                };
                this.lpGoals.placeholder = fallback[subject]
                    || '请输入内容！如：认识分数的概念，能读写简单分数';
            });
    }

    buildHoursSuggestions() {
        const stage = this.lpStage.value;
        const subject = this.lpSubject.value;
        const grade = this.lpGrade.value;

        let suggestions = [...this.hoursSuggestions];
        if (stage && subject && grade) {
            const prefix = `${stage}${grade}${subject}`;
            suggestions = suggestions.map(s => `${prefix} — ${s}`);
        }

        let datalist = document.getElementById('lpHoursList');
        if (!datalist) {
            datalist = document.createElement('datalist');
            datalist.id = 'lpHoursList';
            document.body.appendChild(datalist);
        }
        datalist.innerHTML = suggestions.map(s => `<option value="${s}">`).join('');
    }

    // ===== 进度弹窗 =====
    showProgressModal() {
        this.progressModal.style.display = 'flex';
        this.pmFooter.style.display = 'none';
        this.pmClose.style.display = 'flex'; // 始终可见，用户可随时停止
        // 重置所有阶段状态
        this.pmBody.querySelectorAll('.pm-stage').forEach(s => {
            s.classList.remove('active', 'done', 'error');
            const icon = s.querySelector('.pm-stage-icon');
            icon.className = 'pm-stage-icon pending';
            icon.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/></svg>';
            s.querySelector('.pm-stage-status').textContent = '等待中';
        });
    }

    updateProgressStage(stage) {
        const el = this.pmBody.querySelector(`[data-stage="${stage}"]`);
        if (!el) return;
        // 将之前所有 stage 标记为 done
        const allStages = this.pmBody.querySelectorAll('.pm-stage');
        let passed = true;
        allStages.forEach(s => {
            const sStage = s.dataset.stage;
            if (sStage === stage) {
                s.classList.remove('pending'); s.classList.add('active');
                const icon = s.querySelector('.pm-stage-icon');
                icon.className = 'pm-stage-icon running';
                icon.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 11-6.219-8.56"/></svg>';
                s.querySelector('.pm-stage-status').textContent = '处理中...';
                passed = false;
            } else if (passed) {
                s.classList.remove('pending', 'active'); s.classList.add('done');
                const icon = s.querySelector('.pm-stage-icon');
                icon.className = 'pm-stage-icon done';
                icon.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 6L9 17l-5-5"/></svg>';
                s.querySelector('.pm-stage-status').textContent = '✓ 完成';
            }
        });
    }

    completeProgressModal(success) {
        const allStages = this.pmBody.querySelectorAll('.pm-stage');
        allStages.forEach(s => {
            s.classList.remove('active');
            s.classList.add(success ? 'done' : 'error');
            const icon = s.querySelector('.pm-stage-icon');
            if (success) {
                icon.className = 'pm-stage-icon done';
                icon.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 6L9 17l-5-5"/></svg>';
                s.querySelector('.pm-stage-status').textContent = '✓ 完成';
            } else {
                icon.className = 'pm-stage-icon error';
                icon.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M15 9l-6 6M9 9l6 6"/></svg>';
                s.querySelector('.pm-stage-status').textContent = '失败';
            }
        });
        document.querySelector('.pm-title').textContent = success ? '生成完成' : '生成失败';
        // 仅当工作台内容已就绪时才显示"查看教案"按钮
        this.pmViewResult.style.display = (success && this._pendingWorkbenchHtml) ? '' : 'none';
        this.pmFooter.style.display = 'flex';
        this.pmClose.style.display = 'flex';
    }

    hideProgressModal() {
        this.progressModal.style.display = 'none';
    }

    // ===== Copilot 分步确认 UI（严格手动控制流水线） =====
    updateCopilotSteps(currentStage) {
        const steps = ['analysis','design','development','review','format'];
        const names = ['教学分析','教学设计','教学过程','质量评估','排版输出'];
        let html = '<div class="copilot-steps">';
        let passed = true;
        for (let i = 0; i < steps.length; i++) {
            const cls = steps[i] === currentStage ? 'active' : passed ? 'done' : 'pending';
            const icon = cls === 'active' ? '⏳' : cls === 'done' ? '✓' : '○';
            html += `<span class="copilot-step ${cls}">${icon} ${names[i]}</span>`;
            if (steps[i] === currentStage) passed = false;
        }
        html += '</div>';
        let el = document.getElementById('copilotStepBar');
        if (!el) { el = document.createElement('div'); el.id = 'copilotStepBar'; this.lessonMessages.prepend(el); }
        el.innerHTML = html;
    }

    addCopilotConfirmUI(stage, token) {
        const panel = document.createElement('div');
        panel.className = 'copilot-confirm';
        panel.innerHTML = `
            <div class="copilot-bar">
                <span>⏳ ${this.copilotStageLabel(stage)}阶段完成 — 请选择：</span>
                <button class="copilot-btn copilot-continue">确认继续</button>
                <button class="copilot-btn copilot-modify">修改后继续</button>
                <button class="copilot-btn copilot-terminate">停止生成</button>
            </div>
            <input class="copilot-instruction" placeholder="输入修改意见后点「修改后继续」" style="display:none">`;
        this.lessonMessages.appendChild(panel);
        this.lessonMessages.scrollTop = this.lessonMessages.scrollHeight;

        panel.querySelector('.copilot-continue').onclick = () => {
            panel.innerHTML = '<div class="copilot-bar"><span>⏳ 已确认，继续中...</span></div>';
            this.sendCopilotAction(token, stage, 'continue', '');
        };
        panel.querySelector('.copilot-modify').onclick = () => {
            const input = panel.querySelector('.copilot-instruction');
            if (input.style.display === 'none') {
                input.style.display = 'block'; input.focus();
            } else {
                const inst = input.value.trim() || '请优化内容';
                panel.innerHTML = '<div class="copilot-bar"><span>⏳ 已提交修改意见...</span></div>';
                this.sendCopilotAction(token, stage, 'revise', inst);
            }
        };
        panel.querySelector('.copilot-terminate').onclick = () => {
            if (confirm('确定要停止教案生成吗？已生成的内容将保留。')) {
                panel.innerHTML = '<div class="copilot-bar terminate"><span>⏹ 已停止生成</span></div>';
                this.sendCopilotAction(token, stage, 'terminate', '');
                // 恢复表单
                this.lessonPanel.style.display = '';
                document.querySelector('.lesson-submit-bar').style.display = '';
                this.showToast('已停止生成，可重新开始', 'error');
            }
        };
    }

    copilotStageLabel(s) {
        return {analysis:'教学分析',design:'教学设计',development:'教学过程',review:'质量评估',format:'排版输出'}[s] || s;
    }

    async sendCopilotAction(token, stage, action, content) {
        try {
            await this.apiFetch(this.apiBase + '/lesson_plan/action', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token, stage, action, instruction: content })
            });
        } catch (e) { console.error('Copilot action failed', e); }
    }

    // ===== 教案生成 =====
    validateField(el) {
        const raw = (el.value || '').trim();
        const ok  = raw.length > 0 && raw !== 'undefined' && raw !== 'null';
        const group = el.closest('.form-group');
        if (!ok) {
            el.classList.add('error');
            if (group) {
                let errEl = group.querySelector('.field-error');
                if (!errEl) {
                    errEl = document.createElement('div'); errEl.className = 'field-error';
                    group.appendChild(errEl);
                }
                errEl.textContent = '请输入内容！';
                errEl.classList.add('show');
            }
            return false;
        }
        el.classList.remove('error');
        if (group) {
            const errEl = group.querySelector('.field-error');
            if (errEl) errEl.classList.remove('show');
        }
        return true;
    }

    clearFieldError(el) { el.classList.remove('error'); }

    async generateLessonPlan() {
        // 校验必填字段
        const fields = [
            { el: this.lpStage,   label: '学段' },
            { el: this.lpGrade,   label: '年级' },
            { el: this.lpSubject, label: '学科' },
            { el: this.lpHours,   label: '课时' },
            { el: this.lpGoals,   label: '教学目标' }
        ];
        let valid = true;
        fields.forEach(({ el }) => { if (!this.validateField(el)) valid = false; });
        if (!valid) { this.showToast('请完整填写必填字段', 'error'); return; }
        if (this.isStreaming) return;

        // 先上传参考资料（教案生成模式专属）
        if (this.uploadedFiles.length > 0) {
            await this.uploadAllWithProgress();
        }

        const params = {
            stage: this.lpStage.value,
            grade: this.gradeToNumber(this.lpGrade.value),
            subject: this.lpSubject.value,
            hours: parseInt(this.lpHours.value) || 1,
            goals: this.lpGoals.value.trim(),
            mode: this.lpMode.value,
            Id: this.sessionId, Question: this.lpStage.value + this.lpGrade.value + this.lpSubject.value
        };

        // 个性化上下文字段（2026-08-18 新增, 全可选）
        if (this.lpStudentProfile) params.studentProfile = this.lpStudentProfile.value.trim();
        if (this.lpKeyPoints) params.keyPoints = this.lpKeyPoints.value.trim();
        if (this.lpStylePreference) params.stylePreference = this.lpStylePreference.value.trim();
        if (this.lpAssignment) params.assignmentRequirement = this.lpAssignment.value.trim();
        if (this.lpSpecial) params.specialRequirements = this.lpSpecial.value.trim();

        // Copilot 模式：不弹窗，收起表单，直接展示内容区
        if (params.mode === 'copilot') {
            this.lessonPanel.style.display = 'none';
            document.querySelector('.lesson-submit-bar').style.display = 'none';
            this.lessonResult.style.display = 'block';
            this.lessonMessages.innerHTML = '';
            this.addLessonMessage('assistant', '🎯 **分步确认模式** — 每个阶段生成后将等待你确认后再继续');
        } else {
            // Quick 模式：弹窗展示进度
            this.showProgressModal();
        }
        this._lessonAbort = new AbortController();
        const onAbortClose = () => {
            if (this._lessonAbort) this._lessonAbort.abort();
            this.hideProgressModal();
        };
        this.pmClose.addEventListener('click', onAbortClose, { once: true });

        this.lessonResult.style.display = 'block';
        this.lessonMessages.innerHTML = '';
        this._formatReceived = false; this._reviewReceived = false;
        // 兜底：120 秒后若仍未收到 format 事件，自动标记失败
        this._formatTimer = setTimeout(() => {
            if (!this._formatReceived && this.isStreaming) {
                this.completeProgressModal(false);
                this.addLessonMessage('assistant', '生成超时，请检查网络后重试');
            }
        }, 120000);
        this.isStreaming = true; this.btnGenerate.disabled = true;
        this.btnGenerate.textContent = '生成中...';

        let success = false;
        try {
            success = await this.streamLessonPlan(params, this._lessonAbort.signal);
        } catch (err) {
            if (err.name === 'AbortError' || this._lessonAbort.signal.aborted) {
                // 用户主动取消 — 不弹错误
                this.hideProgressModal();
            } else {
                this.completeProgressModal(false);
                this.addLessonMessage('assistant', '生成失败: ' + err.message);
                this.showToast(err.message, 'error');
            }
        } finally {
            clearTimeout(this._formatTimer);
            this.isStreaming = false; this.btnGenerate.disabled = false;
            this.btnGenerate.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg> 生成教案';
            this.lessonPanel.style.display = '';
            document.querySelector('.lesson-submit-bar').style.display = '';
            this._lessonAbort = null;
            if (this._formatReceived && this._pendingWorkbenchHtml) {
                this.completeProgressModal(true);
            }
        }
    }

    async streamLessonPlan(params, abortSignal) {
        const res = await this.apiFetch(this.apiBase + '/lesson_plan', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(params), signal: abortSignal,
            timeout: false  // SSE 流式响应不设超时
        });
        if (!res.ok) throw new Error('服务器返回 ' + res.status + '，请稍后重试');

        let reader;
        try {
            reader = res.body.getReader();
        } catch (e) {
            throw new Error('响应流不可用，请稍后重试');
        }

        const decoder = new TextDecoder();
        let buf = '', hasContent = false;
        try {
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                hasContent = true;
                buf += decoder.decode(value, { stream: true });
                const lines = buf.split('\n'); buf = lines.pop() || '';
                for (const line of lines) {
                    if (!line.startsWith('data:')) continue;
                    const raw = line.slice(5).trim();
                    if (!raw || raw === '[DONE]') continue;
                    try {
                        const m = JSON.parse(raw);
                    if (m.type && m.type.startsWith('stage:')) {
                        const stage = m.type.replace('stage:', '');
                        if (stage === 'content') {
                            // chunk 增量累积拼接（前端维护完整文本）
                            this._stageBuffer = (this._stageBuffer || '') + (m.chunk || m.content || '');
                            const lastBubble = this.lessonMessages.querySelector('.message.assistant:last-of-type .message-bubble');
                            if (lastBubble && this._stageBuffer.length < 2000) {
                                lastBubble.innerHTML = this.renderMd(this._stageBuffer);
                            } else {
                                this._stageBuffer = ''; // 超出显示长度，新开一条消息
                                this.addLessonMessage('assistant', m.chunk || m.content || '');
                            }
                        } else {
                            this._stageBuffer = ''; // 新阶段开始，清空累积 buffer
                            this.updateProgressStage(stage);
                            if (stage === 'format') {
                                this.showWorkbench(m.content || m.data || '');
                                if (m.updated) {
                                    // Review 推送的更新版：静默刷新已打开的工作台
                                    if (this.workbench.style.display === 'flex') this._renderWorkbench();
                                } else {
                                    // 初次 Format 完成：立即展示"查看教案"按钮
                                    this._formatReceived = true;
                                    if (!this._reviewReceived) this.completeProgressModal(true);
                                }
                            }
                            else if (stage === 'review') {
                                this._reviewReceived = true;
                                this.showQualityScore(m.content || m.data || '');
                                this.completeProgressModal(true);
                            }
                        }
                    }
                    else if (m.type === 'analysis_clarify') {
                        // Analysis 意图澄清(2026-08-18): 建议式展示问题, 不阻塞生成
                        const qs = (m.questions || '').replace(/^[-*]\s*/gm, '· ');
                        this.addLessonMessage('assistant',
                            `### 🔍 生成前小确认（可选，不回答也能继续）\n\n` +
                            qs.split('\n').filter(l => l.trim()).map(l => `> ${l.trim()}`).join('\n\n') +
                            `\n\n_你可以直接在对话中补充这些信息，或跳过继续生成。_`);
                    }
                    else if (m.type === 'stage_await') {
                        // Copilot 分步确认：展示阶段内容 + 等待用户确认（三按钮：继续/修改/停止）
                        const stageName = {analysis:'教学分析',design:'教学设计',development:'教学过程',review:'质量评估'}[m.stage] || m.stage;
                        this.addLessonMessage('assistant',
                            `---\n### ✅ ${stageName} 已完成\n\n` + (m.content || '') +
                            `\n\n> 请检查内容后选择操作`);
                        if (m.token) {
                            this.updateCopilotSteps(m.stage);
                            this.addCopilotConfirmUI(m.stage, m.token);
                        }
                    }
                    } catch {}
                }
            }
            // 正常结束 — 所有剩余阶段标为完成
            this.completeProgressModal(true);
            return true;
        } catch (e) {
            // 流中断（BodyStreamBuffer aborted / 网络断开）
            if (hasContent) {
                // 已有部分内容：视为部分成功
                this.completeProgressModal(true);
                this.showToast('生成已部分完成（连接中断）', 'error');
                return true;
            }
            throw new Error('连接中断，请稍后重试');
        }
    }

    addLessonMessage(role, content) {
        const div = document.createElement('div'); div.className = 'message ' + role;
        const body = document.createElement('div'); body.className = 'message-body';
        const bubble = document.createElement('div'); bubble.className = 'message-bubble';
        bubble.innerHTML = this.renderMd(content);
        body.appendChild(bubble); div.appendChild(body);
        this.lessonMessages.appendChild(div);
        this.lessonMessages.scrollTop = this.lessonMessages.scrollHeight;
        return div;
    }

    gradeToNumber(g) {
        const map = {'一年级':1,'二年级':2,'三年级':3,'四年级':4,'五年级':5,'六年级':6,
                     '七年级':7,'八年级':8,'九年级':9,'高一':10,'高二':11,'高三':12};
        return map[g] || parseInt(g) || 1;
    }

    // ===== 教案编辑工作台 =====
    showWorkbench(html) {
        // 准备好工作台内容，但不立即展示（等待用户在进度弹窗中点击"查看教案"）
        this._pendingWorkbenchHtml = html;
        document.getElementById('wbPrint').onclick = () => window.print();
        document.getElementById('wbClose').onclick = () => { this.workbench.style.display = 'none'; };
        // "查看教案"按钮：关闭弹窗 → 打开工作台
        this.pmViewResult.onclick = () => {
            this.hideProgressModal();
            this._renderWorkbench();
        };
    }

    /** 实际渲染工作台内容到 DOM */
    _renderWorkbench() {
        if (!this._pendingWorkbenchHtml) return;
        this.wbContent.innerHTML = this.wrapSections(this._pendingWorkbenchHtml);
        this.buildOutline();
        this.bindSectionEditing();
        this.workbench.style.display = 'flex';
        this._pendingWorkbenchHtml = null;
    }

    wrapSections(html) {
        return html.replace(/<(h[23])[^>]*>(.*?)<\/\1>/gi, '</section><$1>$2</$1><section>')
            .replace(/^<\/section>/, '').replace(/<section>$/, '') || '<section>' + html + '</section>';
    }

    buildOutline() {
        const headings = this.wbContent.querySelectorAll('h2, h3');
        this.wbOutline.innerHTML = '';
        headings.forEach((h) => {
            const item = document.createElement('a');
            item.className = 'wb-outline-item ' + h.tagName.toLowerCase();
            item.textContent = h.textContent; item.href = '#';
            item.addEventListener('click', (e) => {
                e.preventDefault();
                h.scrollIntoView({ behavior: 'smooth', block: 'start' });
                this.wbOutline.querySelectorAll('.active').forEach(el => el.classList.remove('active'));
                item.classList.add('active');
            });
            this.wbOutline.appendChild(item);
        });
    }

    bindSectionEditing() {
        this.wbContent.querySelectorAll('section').forEach(sec => {
            sec.addEventListener('dblclick', () => {
                if (sec.classList.contains('editing')) return;
                sec.classList.add('editing');
                const original = sec.innerHTML;
                const ta = document.createElement('textarea');
                ta.value = sec.textContent;
                sec.innerHTML = ''; sec.appendChild(ta); ta.focus();
                const save = () => { sec.classList.remove('editing'); sec.innerHTML = ta.value.replace(/\n/g, '<br>'); };
                const cancel = () => { sec.classList.remove('editing'); sec.innerHTML = original; };
                ta.addEventListener('blur', save);
                ta.addEventListener('keydown', (e) => {
                    if (e.key === 'Escape') { e.preventDefault(); cancel(); }
                    if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); save(); }
                });
            });
        });
    }

    // ===== 质量评分 =====
    showQualityScore(reviewText) {
        const scores = this.parseScore(reviewText);
        if (!scores) return;
        const panel = document.createElement('div'); panel.className = 'score-panel';
        panel.innerHTML = `<h3>质量评估: ${scores.total}/100</h3>`;
        if (this.canvasSupported()) {
            const canvas = document.createElement('canvas');
            canvas.width = 260; canvas.height = 260; canvas.style.display = 'block'; canvas.style.margin = '8px auto';
            this.drawRadar(canvas, scores); panel.appendChild(canvas);
        } else {
            panel.innerHTML += ['clarity','accuracy','strategy','alignment','format'].map(k =>
                `<div class="score-row"><span>${this.scoreLabel(k)}</span>
                <div class="score-bar"><div style="width:${scores[k]*5}%"></div></div>
                <span>${scores[k]}/20</span></div>`).join('');
        }
        this.lessonMessages.appendChild(panel);
        this.lessonMessages.scrollTop = this.lessonMessages.scrollHeight;
    }

    canvasSupported() { try { return !!document.createElement('canvas').getContext('2d'); } catch { return false; } }

    drawRadar(canvas, scores) {
        const ctx = canvas.getContext('2d'); const w = canvas.width, h = canvas.height;
        const cx = w / 2, cy = h / 2; const r = Math.min(cx, cy) - 40;
        const dims = ['clarity','accuracy','strategy','alignment','format'];
        const labels = ['目标清晰度','内容准确性','策略合理性','课标对齐度','格式规范度'];
        const n = dims.length;
        ctx.clearRect(0, 0, w, h);
        for (let level = 1; level <= 4; level++) {
            ctx.beginPath();
            for (let i = 0; i <= n; i++) {
                const angle = Math.PI / 2 + (2 * Math.PI * (i % n)) / n; const rr = r * level / 4;
                const x = cx + rr * Math.cos(angle); const y = cy - rr * Math.sin(angle);
                i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
            }
            ctx.closePath(); ctx.strokeStyle = level === 4 ? '#c48650' : '#e0dcd6';
            ctx.lineWidth = level === 4 ? 1.5 : 0.5; ctx.stroke();
        }
        for (let i = 0; i < n; i++) {
            const angle = Math.PI / 2 + (2 * Math.PI * i) / n;
            ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(cx + r * Math.cos(angle), cy - r * Math.sin(angle));
            ctx.strokeStyle = '#d0ccc6'; ctx.lineWidth = 0.5; ctx.stroke();
        }
        ctx.beginPath();
        for (let i = 0; i <= n; i++) {
            const angle = Math.PI / 2 + (2 * Math.PI * (i % n)) / n; const val = scores[dims[i % n]] / 20;
            const rr = r * val; const x = cx + rr * Math.cos(angle); const y = cy - rr * Math.sin(angle);
            i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
        }
        ctx.closePath(); ctx.fillStyle = 'rgba(196,134,80,0.2)'; ctx.fill();
        ctx.strokeStyle = '#c48650'; ctx.lineWidth = 2; ctx.stroke();
        for (let i = 0; i < n; i++) {
            const angle = Math.PI / 2 + (2 * Math.PI * i) / n; const val = scores[dims[i]] / 20;
            const x = cx + r * val * Math.cos(angle); const y = cy - r * val * Math.sin(angle);
            ctx.beginPath(); ctx.arc(x, y, 4, 0, Math.PI * 2); ctx.fillStyle = '#c48650'; ctx.fill();
        }
        ctx.font = '11px -apple-system, sans-serif'; ctx.fillStyle = '#2c2420'; ctx.textAlign = 'center';
        for (let i = 0; i < n; i++) {
            const angle = Math.PI / 2 + (2 * Math.PI * i) / n;
            const lx = cx + (r + 25) * Math.cos(angle); const ly = cy - (r + 25) * Math.sin(angle) + 4;
            ctx.fillText(labels[i], lx, ly);
        }
    }

    parseScore(text) {
        const m = text.match(/"score"\s*:\s*(\d+)/);
        if (!m) return null;
        const total = parseInt(m[1]); const dims = {};
        ['clarity','accuracy','strategy','alignment','format'].forEach(k => {
            const dm = text.match(new RegExp(`"${k}"\\s*:\\s*(\\d+)`));
            dims[k] = dm ? parseInt(dm[1]) : Math.round(total / 5);
        });
        dims.total = total; return dims;
    }
    scoreLabel(k) { return {clarity:'目标清晰度',accuracy:'内容准确性',strategy:'策略合理性',alignment:'课标对齐度',format:'格式规范度'}[k] || k; }

    showToast(msg, type) {
        const t = document.createElement('div'); t.className = 'toast ' + type; t.textContent = msg;
        document.body.appendChild(t);
        setTimeout(() => { t.style.opacity = '0'; setTimeout(() => t.remove(), 300); }, 2500);
    }
}

document.addEventListener('DOMContentLoaded', () => new GagneFlowApp());
