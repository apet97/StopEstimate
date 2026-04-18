(function () {
    'use strict';

    var token = null;
    var refreshTimer = null;
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
        scheduleRefresh();
        loadAll();
    }

    function bindActions() {
        var button = document.getElementById('reconcile-button');
        if (button) {
            button.addEventListener('click', function () {
                setStatus('Running manual reconcile...');
                postJson('/api/guard/reconcile').then(function (payload) {
                    renderProjects(payload.projects || []);
                    setStatus('Manual reconcile completed.');
                }).catch(function (error) {
                    setStatus(error.message);
                });
            });
        }
    }

    function loadAll() {
        Promise.all([fetchJson('/api/context'), fetchJson('/api/guard/projects')])
            .then(function (results) {
                renderContext(results[0]);
                renderProjects(results[1].projects || []);
                setStatus('Stop @ Estimate dashboard loaded.');
            })
            .catch(function (error) {
                setStatus(error.message);
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
        if (!response.ok) {
            throw new Error('Backend call failed with status ' + response.status);
        }
        return response.json();
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
        body.innerHTML = '';
        if (!projects || projects.length === 0) {
            table.style.display = 'none';
            empty.style.display = 'block';
            return;
        }
        empty.style.display = 'none';
        table.style.display = 'table';

        projects.forEach(function (project) {
            var tr = document.createElement('tr');
            tr.innerHTML = [
                '<td>' + safe(project.projectName || project.projectId) + '</td>',
                '<td>' + safe(project.status || '-') + '</td>',
                '<td>' + safe(project.reason || '-') + '</td>',
                '<td>' + formatTime(project.trackedTimeMs) + ' / ' + formatTime(project.timeLimitMs) + '</td>',
                '<td>' + formatMoney(project.budgetUsage) + ' / ' + formatMoney(project.budgetLimit) + '</td>',
                '<td>' + safe(String(project.runningEntryCount || 0)) + '</td>',
                '<td>' + formatInstant(project.cutoffAt) + '</td>',
                '<td>' + formatInstant(project.nextResetAt) + '</td>'
            ].join('');
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
        return safe(String(value));
    }

    function formatInstant(value) {
        if (!value) {
            return '-';
        }
        try {
            return new Date(value).toLocaleString();
        } catch (error) {
            return safe(String(value));
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
        if (String(theme).toUpperCase().indexOf('DARK') !== -1) {
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
        setStatus('Requesting refreshed add-on token...');
        window.parent.postMessage({ title: 'refreshAddonToken' }, parentOrigin || '*');
    }

    function onParentMessage(event) {
        if (parentOrigin && event.origin !== parentOrigin) {
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

    function safe(value) {
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
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
