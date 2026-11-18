<?php
// POST /posts/create.php  (logged in, multipart)  fields: text; files: images[] (up to 10)
// Creates a post. Sent with an X-Request-Id: a repeat (retry after a lost connection, or the
// offline queue sending it again) returns the same post and creates nothing new.
declare(strict_types=1);
require_once __DIR__ . '/../lib/posts.php';
method('POST');

$me = current_user_id();
$text = param('text', '') ?? '';

once($me, function () use ($me, $text) {
    $files = uploads('images', 'posts');
    if ($text === '' && !$files) fail('Write something or add a photo');
    if (count($files) > 10) fail('At most 10 photos');

    exec_sql('INSERT INTO posts (user_id, text, created_at) VALUES (?, ?, ?)', [$me, $text, now_ms()]);
    $id = (int) db()->lastInsertId();
    foreach ($files as $position => $file) {
        exec_sql('INSERT INTO post_images (post_id, path, position) VALUES (?, ?, ?)', [$id, $file['path'], $position]);
    }
    return ['post' => posts_json([row('SELECT * FROM posts WHERE id = ?', [$id])], $me)[0]];
});
