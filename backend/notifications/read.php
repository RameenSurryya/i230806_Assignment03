<?php
// POST /notifications/read.php  (logged in): every notification of mine is now seen
// (called when leaving the Notifications screen; clears the bell badge).
declare(strict_types=1);
require_once __DIR__ . '/../lib/api.php';
method('POST');

$me = current_user_id();
exec_sql('UPDATE notifications SET is_read = 1 WHERE user_id = ? AND is_read = 0', [$me]);
respond();
