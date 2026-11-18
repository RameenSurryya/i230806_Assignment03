<?php
// GET /badges.php  (logged in)  ->  {notifications, chats}
// The red numbers in the top bar: unread notifications (bell) and conversations with
// messages I haven't opened yet (messenger). The app asks every 20 seconds while a screen shows.
declare(strict_types=1);
require_once __DIR__ . '/lib/api.php';
method('GET');

$me = current_user_id();
$notifications = row('SELECT COUNT(*) AS n FROM notifications WHERE user_id = ? AND is_read = 0', [$me]);
$chats = row(
    'SELECT COUNT(DISTINCT m.chat_id) AS n FROM messages m
     LEFT JOIN chat_members cm ON cm.chat_id = m.chat_id AND cm.user_id = ?
     WHERE m.receiver_id = ? AND m.deleted = 0 AND m.created_at > COALESCE(cm.read_at, 0)',
    [$me, $me]
);
respond(['notifications' => (int) $notifications['n'], 'chats' => (int) $chats['n']]);
