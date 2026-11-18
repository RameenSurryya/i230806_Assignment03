<?php
// POST /comments/add.php  (logged in, multipart or JSON)
// Fields: post_id, text, parent_id (optional: a reply). File: image (optional photo comment).
// Notifications: the post's author gets "comment"; when replying, the person replied to gets
// "reply". Replies stay one level deep: a reply to a reply is attached to the top comment.
declare(strict_types=1);
require_once __DIR__ . '/../lib/posts.php';
method('POST');

$me = current_user_id();
$postId = int_param('post_id');
$text = param('text', '') ?? '';
$parentId = int_param('parent_id');
$post = row('SELECT * FROM posts WHERE id = ?', [$postId]);
if ($post === null) fail('Post not found', 404);

once($me, function () use ($me, $post, $postId, $text, $parentId) {
    $image = upload('image', 'comments');
    if ($text === '' && $image === null) fail('Write something or add a photo');

    $parent = null;
    if ($parentId > 0) {
        $parent = row('SELECT * FROM comments WHERE id = ? AND post_id = ?', [$parentId, $postId]);
        if ($parent === null) fail('The comment you replied to is gone', 404);
        if ($parent['parent_id'] !== null) $topId = (int) $parent['parent_id'];
        else $topId = (int) $parent['id'];
    }
    exec_sql(
        'INSERT INTO comments (post_id, user_id, parent_id, text, image_path, created_at) VALUES (?, ?, ?, ?, ?, ?)',
        [$postId, $me, $parent === null ? null : $topId, $text, $image['path'] ?? null, now_ms()]
    );
    $id = (int) db()->lastInsertId();

    $author = (int) $post['user_id'];
    if ($parent === null) {
        add_notification($author, $me, 'comment', $postId, $text);
    } else {
        add_notification((int) $parent['user_id'], $me, 'reply', $postId, $text);
        if ((int) $parent['user_id'] !== $author) add_notification($author, $me, 'comment', $postId, $text);
    }
    $count = row('SELECT COUNT(*) AS n FROM comments WHERE post_id = ?', [$postId]);
    return [
        'comment' => comments_json([row('SELECT * FROM comments WHERE id = ?', [$id])], $me)[0],
        'comment_count' => (int) $count['n'],
    ];
});
