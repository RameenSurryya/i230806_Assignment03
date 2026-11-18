<?php
// GET /posts/feed.php?limit=50&author=7  (logged in)  ->  {posts: [...]}, newest first.
// The Home feed: my posts and my friends' posts. With author=<id>, only that person's posts.
declare(strict_types=1);
require_once __DIR__ . '/../lib/posts.php';
method('GET');

$me = current_user_id();
$limit = max(1, min(100, int_param('limit', 50)));
$author = int_param('author');

if ($author > 0) {
    $list = rows("SELECT * FROM posts WHERE user_id = ? ORDER BY created_at DESC LIMIT $limit", [$author]);
} else {
    $list = rows(
        "SELECT * FROM posts
         WHERE user_id = ? OR user_id IN (SELECT friend_id FROM friends WHERE user_id = ?)
         ORDER BY created_at DESC LIMIT $limit",
        [$me, $me]
    );
}
respond(['posts' => posts_json($list, $me)]);
