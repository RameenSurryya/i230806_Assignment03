<?php
// GET /notifications/list.php?limit=30  (logged in)  ->  {notifications: [...]}, newest first.
// Each row: id, type, from (the user who did it), post_id, text, read, created_at, and
// waiting = a friend request that is still waiting for my answer (shows Confirm / Delete).
declare(strict_types=1);
require_once __DIR__ . '/../lib/friends.php';
method('GET');

$me = current_user_id();
$limit = max(1, min(200, int_param('limit', 30)));
$list = rows("SELECT * FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT $limit", [$me]);

$fromIds = array_values(array_unique(array_map(fn($n) => (int) $n['from_id'], $list)));
$users = [];
foreach (users_by_ids($fromIds, $me) as $u) $users[$u['id']] = $u;
$waiting = array_map(
    fn($r) => (int) $r['from_id'],
    rows('SELECT from_id FROM friend_requests WHERE to_id = ?', [$me])
);

$result = [];
foreach ($list as $n) {
    $from = (int) $n['from_id'];
    $result[] = [
        'id' => (int) $n['id'],
        'type' => $n['type'],
        'from_id' => $from,
        'from' => $users[$from] ?? null,
        'post_id' => $n['post_id'] === null ? null : (int) $n['post_id'],
        'text' => $n['text'],
        'read' => (int) $n['is_read'] === 1,
        'created_at' => (int) $n['created_at'],
        'waiting' => $n['type'] === 'friend_request' && in_array($from, $waiting, true),
    ];
}
respond(['notifications' => $result]);
