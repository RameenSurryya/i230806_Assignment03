<?php
// POST /auth/fcm_token.php  (logged in)  body {token}
// Saves this phone's FCM token under me, so the server can send it pushes. The same token is
// taken away from any other account (someone logged out and another person logged in on the
// same phone), so one phone never gets another account's notifications.
declare(strict_types=1);
require_once __DIR__ . '/../lib/api.php';
method('POST');

$me = current_user_id();
$token = trim(required('token'));
exec_sql('UPDATE users SET fcm_token = NULL WHERE fcm_token = ? AND id <> ?', [$token, $me]);
exec_sql('UPDATE users SET fcm_token = ? WHERE id = ?', [$token, $me]);
respond();
