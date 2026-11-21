<?php
// GET /stories/feed.php?author=7  (logged in)  ->  {stories: [...], time}
// Every story I may see right now (mine + my friends', close-friends ones only if allowed),
// oldest first, the order they play in. With author=<id>, only that person's.
// Expired stories are deleted first, so they never come back.
declare(strict_types=1);
require_once __DIR__ . '/../lib/stories.php';
method('GET');

$me = current_user_id();
purge_expired_stories();

$sql = 'SELECT s.* FROM stories s WHERE ' . visible_stories_sql();
$args = [now_ms(), $me, $me, $me];
$author = int_param('author');
if ($author > 0) {
    $sql .= ' AND s.user_id = ?';
    $args[] = $author;
}
$list = rows($sql . ' ORDER BY s.created_at, s.id', $args);
respond(['stories' => stories_json($list, $me), 'time' => now_ms()]);
