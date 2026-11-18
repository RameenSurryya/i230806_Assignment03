<?php
// POST /auth/presence.php  (logged in)  body {"online": true | false}  ->  {time}
// Online / offline status ("Active now", green dots), Assignment 3.
//  - The app calls this every minute while one of its screens is visible (online = true).
//    current_user_id() already sets is_online = 1 and last_seen = now on every request.
//  - When the app goes to the background it sends online = false: the user is shown offline at
//    once, with last_seen = now ("Active 1m ago").
//  - If the app is killed or loses internet it simply stops calling; users.php then counts the
//    user as offline once last_seen is more than 2 minutes old.
// The answer carries the server's clock so the app can compare times with the server.
declare(strict_types=1);
require_once __DIR__ . '/../lib/api.php';
method('POST');

$id = current_user_id();
if (in_array(param('online', '1'), ['0', '', 'false'], true)) {   // JSON false arrives as ''
    exec_sql('UPDATE users SET is_online = 0, last_seen = ? WHERE id = ?', [now_ms(), $id]);
}
respond(['time' => now_ms()]);
