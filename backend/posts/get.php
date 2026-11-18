<?php
// GET /posts/get.php?id=5  (logged in)  ->  {post}: one post (Comments, Reaction picker, chat shares).
declare(strict_types=1);
require_once __DIR__ . '/../lib/posts.php';
method('GET');

$me = current_user_id();
$post = row('SELECT * FROM posts WHERE id = ?', [int_param('id')]);
if ($post === null) fail('Post not found', 404);
respond(['post' => posts_json([$post], $me)[0]]);
