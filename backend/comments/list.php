<?php
// GET /comments/list.php?post_id=5  (logged in)  ->  {comments: [...]}, oldest first.
// Replies carry parent_id (the app puts them one level under their comment).
declare(strict_types=1);
require_once __DIR__ . '/../lib/posts.php';
method('GET');

$me = current_user_id();
$list = rows('SELECT * FROM comments WHERE post_id = ? ORDER BY created_at, id', [int_param('post_id')]);
respond(['comments' => comments_json($list, $me)]);
