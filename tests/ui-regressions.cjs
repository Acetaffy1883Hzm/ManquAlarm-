// Browser tests exercise the shipped assets with a simulated Android bridge.
// They do not represent a test of Xiaomi system permissions or device audio.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { chromium } = require('playwright');

const assets = path.resolve(process.env.HAZEL_TEST_ASSETS || path.join(__dirname, '../app/src/main/assets'));
const legacy = process.argv.includes('--legacy');
const output = process.env.HAZEL_TEST_OUTPUT;
const results = [], errors = [];
const server = http.createServer((req, res) => {
    const name = new URL(req.url, 'http://localhost').pathname.slice(1) || 'index.html';
    if (!['index.html', 'alarm.html', 'app.js', 'style.css', 'hazel.png'].includes(name)) {
        res.writeHead(404); res.end(); return;
    }
    res.setHeader('Content-Type', ({ html: 'text/html', js: 'application/javascript', css: 'text/css', png: 'image/png' })[name.split('.').pop()]);
    res.end(fs.readFileSync(path.join(assets, name)));
});

function installMock() {
    const clone = x => JSON.parse(JSON.stringify(x));
    const state = {
        config: { soundWithoutNotifications: false, allDay: true, timezone: 'device', catchUp: false, pollSeconds: 30,
            reliable: true, boot: true, ringtone: 'starlight', customName: '未选择', volume: 85,
            ramp: true, vibrate: true, duration: 60, snoozeMinutes: 5, quietCalls: true, theme: 'light',
            windows: [{ id: 'night', name: '凌晨守候', start: 60, end: 360, days: 127, enabled: true }] },
        enabled: false, running: false, ringing: false, snapshot: {}, networkError: '', serviceError: '',
        inside: true, zone: 'Asia/Shanghai', deviceZone: 'Asia/Shanghai', version: '1.0.5',
        watchNotification: {status:'stopped',serviceRunning:false,registered:false,channelImportance:2},
        preview: false, now: Date.now(), snoozeAt: 0, testAt: 0,
        permissions: { notifications: false, notificationRuntime: false, notificationAppEnabled: false,
            notificationMismatch: false, notificationPolicy: 'not_revoked', alarmChannel: true, alarmChannelImportance: 4, battery: true,
            overlay: false, fullScreen: true, exact: true, dnd: false, powerSave: false, accessibility: true,
            accessibilityConnected: true, alarmVolume: 14, alarmMax: 15 }
    };
    const mock = window.__mock = { state, pending: 0, saves: 0, reads: 0, pushBeforeSaveReply: true };
    function reply(id, value, ok = true) {
        mock.pending--;
        window.NativeReply(id, ok ? { ok, value: clone(value) } : { ok, error: value });
    }
    window.HazelNative = { request(id, action, data) {
        mock.pending++;
        (mock.actions || (mock.actions = [])).push(action);
        const patch = JSON.parse(data);
        if (action === 'state') {
            mock.reads++;
            if (mock.failState) { mock.failState = false; setTimeout(() => reply(id, '模拟读取失败', false), 25); return; }
            const captured = clone(state);
            if (mock.holdNextState) {
                mock.holdNextState = false;
                mock.releaseState = () => { delete mock.releaseState; reply(id, captured); };
            } else setTimeout(() => reply(id, captured), 25);
            return;
        }
        if (action === 'save') {
            if (mock.failSave) { mock.failSave = false; setTimeout(() => reply(id, '模拟保存失败', false), 0); return; }
            Object.assign(state.config, patch);
            if (patch.timezone) state.zone = patch.timezone === 'device' ? state.deviceZone : patch.timezone;
            const captured = clone(state.config);
            mock.saves++;
            setTimeout(() => {
                // This is the original native push-before-reply order that lost repaint requests.
                if (mock.pushBeforeSaveReply) window.refreshNative();
                reply(id, captured);
            }, 0);
            return;
        }
        if (action === 'zones') {
            setTimeout(() => reply(id, [
                { id: 'Asia/Shanghai', offset: '+08:00', time: '22:00' },
                { id: 'Asia/Tokyo', offset: '+09:00', time: '23:00' },
                { id: 'America/New_York', offset: '-04:00', time: '10:00' }
            ]), 0); return;
        }
        if (action === 'toggle') {
            state.enabled=patch.enabled;state.running=patch.enabled;
            setTimeout(() => reply(id, state), 0); return;
        }
        if (action === 'test') { state.ringing=true;state.alarmTest=true;state.alarmUntil=Date.now()+60000; }
        if (action === 'testLater') state.testAt=Date.now()+15000;
        if (action === 'dismiss') state.ringing=false;
        if (action === 'cancelTest') state.testAt=0;
        if (action === 'permission') mock.lastPermission = patch.kind;
        if (action === 'exportNotificationReport') mock.lastReportOptions = patch;
        if (action === 'notificationProbe') {
            mock.probes = (mock.probes || 0) + 1;
            setTimeout(() => reply(id, state.permissions.notifications ? true : '系统尚未允许通知，请先完成通知授权', state.permissions.notifications), 0); return;
        }
        setTimeout(() => reply(id, action === 'history' ? [] : true), 0);
    } };
}

(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({ headless: true,
        ...(process.env.HAZEL_TEST_CHROMIUM ? { executablePath: process.env.HAZEL_TEST_CHROMIUM } : {}),
        args: ['--no-sandbox'] });
    const context = await browser.newContext({ viewport: { width: 393, height: 852 }, deviceScaleFactor: 1 });
    await context.addInitScript(installMock);
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    const settle = async () => {
        await page.waitForFunction(() => window.__mock.pending === 0);
    };
    const reset = async route => {
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        await page.waitForSelector('[data-route="sound"]');
        await settle();
        if (route) await page.locator(`[data-route="${route}"]`).last().click();
    };
    const selected = async tone => page.locator(`[data-tone="${tone}"]`).evaluate(el => el.classList.contains('selected'));
    const test = async (name, fn) => {
        await fn(); results.push(name); console.log('PASS:', name);
    };
    try {
        await test(legacy ? 'original: save persists but ringtone stays unselected' : 'ringtone selection paints without leaving the sound page', async () => {
            await reset('sound');
            await page.locator('[data-tone="morning"]').click(); await settle();
            assert.equal(await page.evaluate(() => __mock.state.config.ringtone), 'morning');
            assert.equal(await selected('morning'), !legacy);
        });
        await test(legacy ? 'original: permission change does not repaint settings' : 'permission-only change repaints without config changes', async () => {
            await reset('settings');
            await page.evaluate(async () => {
                Object.assign(__mock.state.permissions, { notifications: true, notificationRuntime: true, notificationAppEnabled: true });
                await window.refreshNative();
            });
            assert.equal((await page.locator('[data-permission="notifications"] .badge').innerText()).trim(), legacy ? '去设置' : '已就绪');
        });
        if (!legacy) {
            await test('stale in-flight state cannot undo a committed ringtone', async () => {
                await reset('sound');
                await page.evaluate(() => { __mock.holdNextState = true; window.refreshNative(); });
                await page.waitForFunction(() => !!__mock.releaseState);
                await page.locator('[data-tone="morning"]').click();
                await page.waitForFunction(() => document.querySelector('[data-tone="morning"]').classList.contains('selected'));
                await page.evaluate(() => __mock.releaseState()); await settle();
                assert.equal(await selected('morning'), true);
            });
            await test('forced refresh is queued and its promise waits for completion', async () => {
                await reset('settings');
                await page.evaluate(() => {
                    __mock.holdNextState = true; window.refreshNative();
                    Object.assign(__mock.state.permissions, { notifications: true, notificationRuntime: true, notificationAppEnabled: true });
                    window.refreshNative(true).then(() => { __mock.forceCompleted = true; });
                });
                assert.equal(await page.evaluate(() => !!__mock.forceCompleted), false);
                await page.evaluate(() => __mock.releaseState());
                await page.waitForFunction(() => __mock.forceCompleted);
                assert.equal((await page.locator('[data-permission="notifications"] .badge').innerText()).trim(), '已就绪');
            });
            await test('rapid ringtone selections retain the last choice and another setting', async () => {
                await reset('sound');
                await page.evaluate(() => {
                    for (const tone of ['morning', 'urgent', 'system']) document.querySelector(`[data-tone="${tone}"]`).click();
                    const input = document.querySelector('#volume'); input.value = '63'; input.dispatchEvent(new Event('change', { bubbles: true }));
                }); await settle();
                assert.equal(await selected('system'), true);
                assert.equal(await page.locator('#volume').inputValue(), '63');
                assert.equal(await page.locator('.tone.selected').count(), 1);
            });
            await test('switch changes paint their saved state in the same page', async () => {
                await reset('sound');
                await page.locator('[data-toggle="ramp"]').click(); await settle();
                assert.equal(await page.locator('[data-toggle="ramp"]').getAttribute('aria-checked'), 'false');
                await page.locator('[data-toggle="ramp"]').click(); await settle();
                assert.equal(await page.locator('[data-toggle="ramp"]').getAttribute('aria-checked'), 'true');
            });
            await test('failed save retains the prior selection and reports the error', async () => {
                await reset('sound');
                await page.evaluate(() => { __mock.failSave = true; });
                await page.locator('[data-tone="urgent"]').click(); await settle();
                assert.equal(await selected('starlight'), true);
                assert.match(await page.locator('#toast').innerText(), /模拟保存失败/);
            });
            await test('native save without an extra push also paints immediately', async () => {
                await reset('sound');
                await page.evaluate(() => { __mock.pushBeforeSaveReply = false; });
                await page.locator('[data-tone="morning"]').click(); await settle();
                assert.equal(await selected('morning'), true);
            });
            await test('mismatched and revoked permissions remain visibly not ready', async () => {
                await reset('settings');
                for (const [runtime, app] of [[true, false], [false, true], [true, true], [false, false]]) {
                    await page.evaluate(async ([runtime, app]) => {
                        Object.assign(__mock.state.permissions, { notificationRuntime: runtime, notificationAppEnabled: app,
                            notificationMismatch: runtime !== app, notifications: runtime && app });
                        await window.refreshNative();
                    }, [runtime, app]);
                    assert.equal((await page.locator('[data-permission="notifications"] .badge').innerText()).trim(), runtime && app ? '已就绪' : '去设置');
                }
            });
            await test('separate system-notification-settings action remains accessible', async () => {
                await page.locator('[data-permission="notificationSettings"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.lastPermission), 'notificationSettings');
            });
            await test('a failed state read does not permanently lock the refresh queue', async () => {
                await page.evaluate(async () => { __mock.failState = true; await window.refreshNative(true); });
                await page.evaluate(async () => {
                    Object.assign(__mock.state.permissions, { notifications: true, notificationRuntime: true, notificationAppEnabled: true });
                    await window.refreshNative(true);
                });
                assert.equal((await page.locator('[data-permission="notifications"] .badge').innerText()).trim(), '已就绪');
            });
            await test('notification help follows real permission changes and offers both settings routes', async () => {
                await reset('settings');
                await page.evaluate(() => { __mock.state.xiaomi = true; });
                await page.locator('[data-action="notificationHelp"]').click(); await settle();
                assert.match(await page.locator('[data-notification-help]').innerText(), /尚未允许/);
                assert.match(await page.locator('#modal').innerText(), /小米/);
                await page.locator('#modal [data-permission="standardNotifications"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.lastPermission), 'standardNotifications');
                await page.locator('#modal [data-permission="appPermissions"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.lastPermission), 'appPermissions');
                await page.evaluate(async () => {
                    Object.assign(__mock.state.permissions, {notifications:true,notificationRuntime:true,notificationAppEnabled:true});
                    await window.refreshNative(true);
                });
                assert.match(await page.locator('[data-notification-help]').innerText(), /系统已允许发送通知/);
                await page.locator('#modal [data-action="notificationProbe"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.probes), 1);
                await page.evaluate(async () => {
                    Object.assign(__mock.state.permissions, {notifications:false,notificationRuntime:false,notificationAppEnabled:false});
                    await window.refreshNative(true);
                });
                assert.match(await page.locator('[data-notification-help]').innerText(), /尚未允许/);
                assert.equal(await page.locator('#modal [data-action="notificationProbe"]').isDisabled(), true);
                assert.equal(await page.evaluate(() => __mock.probes), 1);
                await page.locator('#modal [data-action="closeModal"]').click();
            });
            await test('custom schedule selection and timezone update stay visible', async () => {
                await reset('schedule');
                await page.locator('[data-all-day="false"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.config.allDay), false);
                await page.locator('[data-action="chooseTimezone"]').click();
                await page.locator('[data-zone="Asia/Tokyo"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.config.timezone), 'Asia/Tokyo');
                assert.match(await page.locator('#content').innerText(), /Asia\/Tokyo/);
            });
            await test('editing a time window completes without route switching', async () => {
                await page.locator('[data-edit-rule="night"]').click();
                await page.locator('#rule-name').fill('凌晨守候修复验证');
                await page.locator('#rule-start').fill('01:30');
                await page.locator('[data-action="saveRule"]').click(); await settle();
                await page.waitForFunction(() => document.querySelector('#modal').hidden);
                assert.match(await page.locator('#content').innerText(), /凌晨守候修复验证/);
                assert.match(await page.locator('#content').innerText(), /01:30/);
            });
            await test('theme selection paints without leaving settings', async () => {
                await page.locator('[data-route="settings"]').click();
                await page.locator('[data-setting="theme"]').selectOption('dark'); await settle();
                assert.equal(await page.locator('body').evaluate(el => el.classList.contains('dark')), true);
                await page.locator('[data-setting="theme"]').selectOption('light'); await settle();
            });
            await test('app name is Manqu and denied permission requires explicit sound consent', async () => {
                await reset('home');
                assert.equal(await page.locator('.brand-name').innerText(), '满区闹钟');
                await page.locator('[data-action="start"]').click(); await settle();
                assert.match(await page.locator('#modal').innerText(), /通知栏和锁屏提醒可能不显示/);
                assert.equal(await page.evaluate(() => __mock.saves), 0);
                assert.equal(await page.evaluate(() => __mock.state.enabled), false);
                await page.locator('#modal [data-action="closeModal"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.config.soundWithoutNotifications), false);
                assert.equal(await page.evaluate(() => __mock.actions.includes('toggle')), false);
            });
            await test('consenting starts the requested watch while permission remains denied', async () => {
                await page.locator('[data-action="start"]').click();
                await page.locator('[data-action="confirmCompatibility"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.enabled), true);
                assert.equal(await page.evaluate(() => __mock.state.config.soundWithoutNotifications), true);
                assert.equal(await page.evaluate(() => __mock.state.permissions.notifications), false);
                await page.locator('[data-route="settings"]').last().click();
                assert.equal(await page.locator('[data-toggle="soundWithoutNotifications"]').getAttribute('aria-checked'), 'true');
                assert.equal(await page.locator('[data-permission="notifications"] .badge').innerText(), '去设置');
            });
            await test('failed consent save cannot start monitoring and can be retried', async () => {
                await reset('home');
                await page.locator('[data-action="start"]').click();
                await page.evaluate(() => { __mock.failSave=true; });
                await page.locator('[data-action="confirmCompatibility"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.enabled), false);
                assert.equal(await page.evaluate(() => __mock.state.config.soundWithoutNotifications), false);
                assert.equal(await page.locator('#modal').isVisible(), true);
                assert.match(await page.locator('#toast').innerText(), /模拟保存失败/);
                await page.locator('[data-action="confirmCompatibility"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.enabled), true);
            });
            await test('normal grant starts monitoring without enabling compatibility', async () => {
                await reset('home');
                await page.evaluate(async () => {
                    Object.assign(__mock.state.permissions, { notifications:true,notificationRuntime:true,notificationAppEnabled:true });
                    await window.refreshNative(true);
                });
                await page.locator('[data-action="start"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.enabled), true);
                assert.equal(await page.evaluate(() => __mock.state.config.soundWithoutNotifications), false);
                assert.equal(await page.locator('#modal').isVisible(), false);
            });
            await test('denied immediate test resumes after consent and has an immediate stop', async () => {
                await reset('home');
                await page.locator('[data-action="testDialog"]').click();
                await page.locator('[data-action="test"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.actions.includes('test')), false);
                await page.locator('[data-action="confirmCompatibility"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.ringing), true);
                await page.locator('[data-action="dismiss"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.ringing), false);
            });
            await test('denied delayed test resumes after consent and can be cancelled', async () => {
                await reset('home');
                await page.locator('[data-action="testDialog"]').click();
                await page.locator('[data-action="testLater"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.testAt), 0);
                await page.locator('[data-action="confirmCompatibility"]').click(); await settle();
                assert.ok(await page.evaluate(() => __mock.state.testAt>Date.now()));
                await page.locator('[data-action="cancelTest"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.testAt), 0);
            });
            await test('revocation keeps consented tests available; opt-out restores the block', async () => {
                await reset('settings');
                await page.locator('[data-toggle="soundWithoutNotifications"]').click();
                assert.equal(await page.evaluate(() => __mock.saves), 0);
                await page.locator('[data-action="confirmCompatibility"]').click(); await settle();
                await page.evaluate(async () => {
                    Object.assign(__mock.state.permissions, { notifications:true,notificationRuntime:true,notificationAppEnabled:true });
                    await window.refreshNative(true);
                    Object.assign(__mock.state.permissions, { notifications:false,notificationRuntime:false,notificationAppEnabled:false });
                    await window.refreshNative(true);
                });
                await page.locator('[data-action="testDialog"]').click();
                await page.locator('[data-action="test"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.ringing), true);
                await page.evaluate(async () => { __mock.state.ringing=false;await window.refreshNative(true); });
                await page.locator('[data-toggle="soundWithoutNotifications"]').click(); await settle();
                assert.equal(await page.locator('[data-toggle="soundWithoutNotifications"]').getAttribute('aria-checked'), 'false');
                await page.locator('[data-action="testDialog"]').click();
                await page.locator('[data-action="test"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.state.ringing), false);
                assert.equal(await page.locator('[data-action="confirmCompatibility"]').isVisible(), true);
                await page.locator('#modal [data-action="closeModal"]').click();
            });
            await test('overlay is optional and its settings route does not change notification grants', async () => {
                await page.locator('[data-permission="overlay"]').click(); await settle();
                assert.equal(await page.evaluate(() => __mock.lastPermission), 'overlay');
                assert.equal(await page.evaluate(() => __mock.state.permissions.notifications), false);
            });
            await test('policy denial is distinct and does not reopen an ineffective runtime request', async () => {
                await reset('settings');
                await page.evaluate(async () => { __mock.state.permissions.notificationPolicy='revoked'; await window.refreshNative(true); });
                assert.equal(await page.locator('[data-permission="notifications"] .badge').innerText(), '策略限制');
                await page.locator('[data-permission="notifications"]').click(); await settle();
                assert.match(await page.locator('[data-notification-help]').innerText(), /受系统策略限制/);
                assert.match(await page.locator('[data-notification-advice]').innerText(), /还不能确定/);
                assert.equal(await page.locator('#modal [data-action="notificationProbe"]').isDisabled(), true);
                assert.equal(await page.evaluate(() => (__mock.actions || []).includes('permission')), false);
                if (output) { fs.mkdirSync(output, { recursive:true });await page.screenshot({path:path.join(output,'policy-help.png'),fullPage:true}); }
            });
            await test('unknown policy reads do not falsely claim a block or suppress ordinary requests', async () => {
                await page.evaluate(async () => { __mock.state.permissions.notificationPolicy='unknown';await window.refreshNative(true); });
                assert.match(await page.locator('[data-notification-help]').innerText(), /暂时无法读取/);
                assert.doesNotMatch(await page.locator('[data-notification-advice]').innerText(), /系统报告通知权限受策略限制/);
                await page.locator('#modal [data-action="closeModal"]').click();
                await page.locator('[data-permission="notifications"]').click();await settle();
                assert.equal(await page.evaluate(() => __mock.lastPermission), 'notifications');
            });
            await test('policy removal and real grant update help and reenable the notification test', async () => {
                await page.evaluate(async () => { __mock.state.permissions.notificationPolicy='revoked';await window.refreshNative(true); });
                await page.locator('[data-permission="notifications"]').click();await settle();
                await page.evaluate(async () => {
                    Object.assign(__mock.state.permissions,{notificationPolicy:'not_revoked',notificationRuntime:true,notificationAppEnabled:true,notifications:true});
                    await window.refreshNative(true);
                });
                assert.match(await page.locator('[data-notification-help]').innerText(), /系统已允许发送通知/);
                assert.equal(await page.locator('#modal [data-action="notificationProbe"]').isDisabled(),false);
                await page.locator('#modal [data-action="notificationProbe"]').click();await settle();
                assert.equal(await page.evaluate(() => __mock.probes),1);
                await page.locator('#modal [data-action="closeModal"]').click();
                assert.equal(await page.locator('[data-permission="notifications"] .badge').innerText(),'已就绪');
            });
            await test('component export requires the explicit choice and diagnostics-only stays available', async () => {
                await reset('settings');
                await page.locator('[data-action="notificationReport"]').click();await settle();
                assert.equal(await page.evaluate(() => __mock.actions.includes('exportNotificationReport')),false);
                await page.locator('[data-action="reportOnly"]').click();await settle();
                assert.equal(await page.evaluate(() => __mock.lastReportOptions.includeComponents),false);
                await page.locator('[data-action="reportWithComponents"]').click();await settle();
                assert.equal(await page.evaluate(() => __mock.lastReportOptions.includeComponents),true);
                assert.equal(await page.evaluate(() => __mock.state.permissions.notifications),false);
                await page.locator('#modal [data-action="closeModal"]').click();
            });
            await test('running service with blocked notifications is not shown as a visible persistent card', async () => {
                await reset('settings');
                await page.evaluate(async () => {
                    __mock.state.enabled=true;__mock.state.running=true;
                    __mock.state.watchNotification.status='permission_blocked';
                    await window.refreshNative();
                });
                assert.match(await page.locator('[data-watch-notice]').innerText(),/通知被系统拦截/);
                assert.match(await page.locator('[data-watch-notice]').innerText(),/前台服务正在运行/);
                assert.equal(await page.locator('[data-permission="notifications"] .badge').innerText(),'去设置');
            });
            await test('watch status updates in place for grant, channel denial and interrupted service', async () => {
                for (const [status,copy] of [['registered','前台守候已运行'],['channel_blocked','守候通知通道已关闭'],['not_posted','系统暂未列出守候通知'],['interrupted','后台服务待恢复'],['stopped','尚未开启']]) {
                    await page.evaluate(async status => {__mock.state.watchNotification.status=status;await window.refreshNative();},status);
                    assert.match(await page.locator('[data-watch-notice]').innerText(),new RegExp(copy));
                }
            });
            await test('persistent notification channel uses its own settings entry', async () => {
                await page.locator('[data-watch-notice] [data-permission="watchChannel"]').click();await settle();
                assert.equal(await page.evaluate(() => __mock.lastPermission),'watchChannel');
                await page.locator('[data-watch-notice] [data-action="watchNotificationHelp"]').click();await settle();
                assert.equal(await page.locator('[data-notification-help]').count(),1);
                await page.locator('#modal [data-action="closeModal"]').click();
            });
            if (output) {
                fs.mkdirSync(output, { recursive: true });
                await page.screenshot({ path: path.join(output, 'permissions-fixed.png'), fullPage: true });
                await page.locator('[data-route="sound"]').click();
                await page.locator('[data-tone="morning"]').click(); await settle();
                await page.screenshot({ path: path.join(output, 'sound-fixed.png'), fullPage: true });
            }
            assert.deepEqual(errors, [], 'no browser script errors');
        }
        console.log(`PASS: ${results.length} ${legacy ? 'original regression reproductions' : 'UI and bridge regression scenarios'}`);
        if (output) fs.writeFileSync(path.join(output, legacy ? 'original-regressions.json' : 'ui-regressions.json'), JSON.stringify({ legacy, results, errors }, null, 2));
    } finally {
        await browser.close(); server.close();
    }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
