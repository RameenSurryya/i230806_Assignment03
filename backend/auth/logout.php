<?php
// POST /auth/logout.php  (logged in)
// Invalidates this token, marks the user offline and forgets the phone's push token,
// so a logged-out phone gets no more notifications.
declare(strict_types=1);
require_once __DIR__ . '/../lib/api.php';
method('POST');

$id = current_user_id();
exec_sql('UPDATE sessions SET revoked = 1 WHERE token_hash = ?', [hash('sha256', bearer_token())]);
exec_sql('UPDATE users SET is_online = 0, last_seen = ?, fcm_token = NULL WHERE id = ?', [now_ms(), $id]);
respond();