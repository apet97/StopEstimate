(function () {
    'use strict';

    var DARK_THEMES = ['DARK', 'DARKBLUE'];

    var token = null;
    var refreshTimer = null;
    var loadGeneration = 0;
    var parentOrigin = resolveParentOrigin();

    function init() {
        token = extractToken();
        if (!token) {
            setStatus('Missing auth_token in iframe URL.');
            return;
        }

        var claims = decodeClaims(token);
        applyThemeAndLanguage(claims);
        setText('workspace-id', claims.workspaceId || '-');
        setText('user-id', claims.userId || claims.user || '-');
        bindActions();
        window.addEventListener('message', onParentMessage);
        window.addEventListener('pagehide', onPageHide);
        scheduleRefresh();
        loadAll();
    }

    function bindActions() {
        var button = document.getElementById('reconcile-button');
        if (!button) {
            return;
        }
        button.addEventListener('click', function () {
            if (button.disabled) {
                return;
            }
            button.disabled = true;
            setStatus('Running manual reconcile...');
            postJson('/api/guard/reconcile').then(function (payload) {
                renderProjects(payload.projects || []);
                setStatus('Manual reconcile completed.');
            }).catch(function (error) {
                setStatus(error.message);
            }).then(function () {
                button.disabled = false;
            });
        });
    }

    function loadAll() {
        // FE-06: a token refresh may trigger a second loadAll while the first is still in
        // flight. Tag each call with a monotonically increasing generation and drop any
        // response whose generation is no longer current.
        var gen = ++loadGeneration;
        Promise.allSettled([
            fetchJson('/api/context'),
            fetchJson('/api/guard/projects'),
            fetchJson('/api/guard/events?limit=50')
        ])
            .then(function (results) {
                if (gen !== loadGeneration) {
                    return;
                }
                var context = results[0];
                var projects = results[1];
                var events = results[2];
                var messages = [];
                if (context.status === 'fulfilled') {
                    renderContext(context.value);
                } else {
                    messages.push('context: ' + context.reason.message);
                }
                if (projects.status === 'fulfilled') {
                    renderProjects(projects.value.projects || []);
                } else {
                    messages.push('projects: ' + projects.reason.message);
                }
                if (events.status === 'fulfilled') {
                    renderEvents(events.value.events || []);
                } else {
                    messages.push('events: ' + events.reason.message);
                }
                if (messages.length === 0) {
                    setStatus('Stop @ Estimate dashboard loaded.');
                } else {
                    setStatus('Partial load — ' + messages.join('; '));
                }
            });
    }

    function fetchJson(url) {
        return fetch(url, {
            headers: {
                'X-Addon-Token': token
            }
        }).then(handleResponse);
    }

    function postJson(url) {
        return fetch(url, {
            method: 'POST',
            headers: {
                'X-Addon-Token': token
            }
        }).then(handleResponse);
    }

    function handleResponse(response) {
        if (response.ok) {
            return response.json();
        }
        // FE-04: surface the server-provided error message instead of the opaque status line.
        return response.text().then(function (body) {
            var detail = null;
            if (body) {
                try {
                    var parsed = JSON.parse(body);
                    detail = parsed.message || parsed.error;
                } catch (_ignored) {
                    detail = body;
                }
            }
            throw new Error(detail || ('Backend call failed with status ' + response.status));
        });
    }

    function renderContext(payload) {
        setText('addon-status', payload.status || '-');
        setText('enabled', String(Boolean(payload.enabled)));
        setText('reset-cadence', payload.defaultResetCadence || 'NONE');
        var pill = document.getElementById('mode-pill');
        if (pill) {
            pill.textContent = payload.enabled ? 'Enforcing' : 'Disabled';
            pill.className = 'status-pill' + (payload.enabled ? '' : ' warn');
        }
    }

    function renderProjects(projects) {
        var body = document.getElementById('projects-body');
        var table = document.getElementById('projects-table');
        var empty = document.getElementById('projects-empty');
        if (!body || !table || !empty) {
            return;
        }
        // FE-01: build rows with DOM APIs and textContent so untrusted field values cannot
        // escape into HTML context no matter what columns are added later.
        while (body.firstChild) {
            body.removeChild(body.firstChild);
        }
        if (!projects || projects.length === 0) {
            table.style.display = 'none';
            empty.style.display = 'block';
            return;
        }
        empty.style.display = 'none';
        table.style.display = 'table';

        projects.forEach(function (project) {
            var cells = [
                project.projectName || project.projectId || '-',
                project.status || '-',
                project.reason || '-',
                formatTime(project.trackedTimeMs) + ' / ' + formatTime(project.timeLimitMs),
                formatMoney(project.budgetUsage) + ' / ' + formatMoney(project.budgetLimit),
                String(project.runningEntryCount || 0),
                formatInstant(project.cutoffAt),
                formatInstant(project.nextResetAt)
            ];
            var tr = document.createElement('tr');
            cells.forEach(function (text) {
                var td = document.createElement('td');
                td.textContent = text;
                tr.appendChild(td);
            });
            body.appendChild(tr);
        });
    }

    function renderEvents(events) {
        var body = document.getElementById('events-body');
        var table = document.getElementById('events-table');
        var empty = document.getElementById('events-empty');
        if (!body || !table || !empty) {
            return;
        }
        // FE-11: same DOM+textContent discipline as renderProjects so no field can escape HTML.
        while (body.firstChild) {
            body.removeChild(body.firstChild);
        }
        if (!events || events.length === 0) {
            table.style.display = 'none';
            empty.style.display = 'block';
            return;
        }
        empty.style.display = 'none';
        table.style.display = 'table';

        events.forEach(function (event) {
            var cells = [
                formatInstant(event.createdAt),
                event.eventType || '-',
                event.guardReason || '-',
                event.source || '-',
                event.projectId || '—'
            ];
            var tr = document.createElement('tr');
            cells.forEach(function (text) {
                var td = document.createElement('td');
                td.textContent = text;
                tr.appendChild(td);
            });
            body.appendChild(tr);
        });
    }

    function formatTime(value) {
        var ms = Number(value || 0);
        if (!ms || ms <= 0) {
            return '-';
        }
        var totalMinutes = Math.floor(ms / 60000);
        var hours = Math.floor(totalMinutes / 60);
        var minutes = totalMinutes % 60;
        return hours + 'h ' + minutes + 'm';
    }

    function formatMoney(value) {
        if (value === null || value === undefined || value === '') {
            return '-';
        }
        return String(value);
    }

    function formatInstant(value) {
        if (!value) {
            return '-';
        }
        try {
            return new Date(value).toLocaleString();
        } catch (error) {
            return String(value);
        }
    }

    function extractToken() {
        var params = new URLSearchParams(window.location.search);
        var value = params.get('auth_token');
        if (value) {
            params.delete('auth_token');
            var next = window.location.pathname + (params.toString() ? '?' + params.toString() : '');
            history.replaceState({}, document.title, next);
        }
        return value;
    }

    function decodeClaims(jwt) {
        try {
            var payload = jwt.split('.')[1];
            var normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
            var json = atob(normalized);
            return JSON.parse(json);
        } catch (error) {
            setStatus('Failed to decode auth_token payload.');
            return {};
        }
    }

    function applyThemeAndLanguage(claims) {
        var theme = claims.theme || 'DEFAULT';
        var language = claims.language || 'en';
        document.documentElement.lang = String(language).toLowerCase();
        // FE-08: match against a known list of dark-theme names rather than a substring of
        // an unverified JWT claim. A future claim like "DARKISH" should not trip dark mode.
        if (DARK_THEMES.indexOf(String(theme).toUpperCase()) !== -1) {
            document.body.classList.add('theme-dark');
        } else {
            document.body.classList.remove('theme-dark');
        }
    }

    function scheduleRefresh() {
        clearInterval(refreshTimer);
        refreshTimer = setInterval(function () {
            requestTokenRefresh();
        }, 25 * 60 * 1000);
    }

    function requestTokenRefresh() {
        // SEC-07: refuse to post a token-refresh request when we do not know the parent
        // origin. The previous fallback of '*' would broadcast to any listener, and the
        // onParentMessage guard below also became permissive when parentOrigin was null,
        // so a malicious frame could inject a replacement token.
        if (!parentOrigin) {
            setStatus('Cannot refresh token: parent origin is unknown in this browser.');
            return;
        }
        setStatus('Requesting refreshed add-on token...');
        window.parent.postMessage({ title: 'refreshAddonToken' }, parentOrigin);
    }

    function onParentMessage(event) {
        // SEC-07: if we never resolved the parent origin, reject all incoming messages —
        // we cannot tell an add-on host message apart from a cross-frame attacker.
        if (!parentOrigin || event.origin !== parentOrigin) {
            return;
        }

        var data = normalizeMessageData(event.data);
        if (!data || data.title !== 'refreshAddonToken' || !data.body) {
            return;
        }

        token = data.body;
        var claims = decodeClaims(token);
        applyThemeAndLanguage(claims);
        setText('workspace-id', claims.workspaceId || '-');
        setText('user-id', claims.userId || claims.user || '-');
        loadAll();
        setStatus('Token refreshed successfully.');
    }

    function onPageHide() {
        clearInterval(refreshTimer);
        window.removeEventListener('message', onParentMessage);
    }

    function normalizeMessageData(value) {
        if (!value) {
            return null;
        }
        if (typeof value === 'string') {
            try {
                return JSON.parse(value);
            } catch (error) {
                return null;
            }
        }
        return typeof value === 'object' ? value : null;
    }

    function resolveParentOrigin() {
        try {
            if (window.location.ancestorOrigins && window.location.ancestorOrigins.length > 0) {
                return window.location.ancestorOrigins[0];
            }
        } catch (error) {
        }
        try {
            if (document.referrer) {
                return new URL(document.referrer).origin;
            }
        } catch (error) {
        }
        return null;
    }

    function setText(id, value) {
        var node = document.getElementById(id);
        if (node) {
            node.textContent = value;
        }
    }

    function setStatus(value) {
        setText('status-line', value);
    }

    init();
})();
