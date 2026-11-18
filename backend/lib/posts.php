<?php
// Posts and comments as the app sees them (Assignment 3): shared by posts/ and comments/.
declare(strict_types=1);
require_once __DIR__ . '/friends.php';

/** "?, ?, ?" for an IN (...) list. */
function marks(array $values): string
{
    return implode(',', array_fill(0, count($values), '?'));
}

/**
 * Turns post rows into API JSON, with their pictures, reactions and authors fetched in a few
 * queries (not one per post). reactions = {"<user id>": "love", ...}.
 */
function posts_json(array $posts, int $viewerId): array
{
    if (!$posts) return [];
    $ids = array_map(fn($p) => (int) $p['id'], $posts);

    $images = [];
    foreach (rows('SELECT post_id, path FROM post_images WHERE post_id IN (' . marks($ids) . ') ORDER BY position', $ids) as $r) {
        $images[(int) $r['post_id']][] = media_url($r['path']);
    }
    $reactions = [];
    foreach (rows('SELECT post_id, user_id, type FROM reactions WHERE post_id IN (' . marks($ids) . ')', $ids) as $r) {
        $reactions[(int) $r['post_id']][(string) $r['user_id']] = $r['type'];
    }
    $comments = [];
    foreach (rows('SELECT post_id, COUNT(*) AS n FROM comments WHERE post_id IN (' . marks($ids) . ') GROUP BY post_id', $ids) as $r) {
        $comments[(int) $r['post_id']] = (int) $r['n'];
    }
    $authorIds = array_values(array_unique(array_map(fn($p) => (int) $p['user_id'], $posts)));
    $authors = [];
    foreach (users_by_ids($authorIds, $viewerId) as $u) $authors[$u['id']] = $u;

    $result = [];
    foreach ($posts as $p) {
        $id = (int) $p['id'];
        $author = (int) $p['user_id'];
        $result[] = [
            'id' => $id,
            'author_id' => $author,
            'author' => $authors[$author] ?? null,
            'text' => $p['text'],
            'image_urls' => $images[$id] ?? [],
            'reactions' => (object) ($reactions[$id] ?? []),
            'comment_count' => $comments[$id] ?? 0,
            'share_count' => (int) $p['share_count'],
            'created_at' => (int) $p['created_at'],
        ];
    }
    return $result;
}

/** Comment rows as API JSON: author, picture URL and likes = ["<user id>", ...]. */
function comments_json(array $comments, int $viewerId): array
{
    if (!$comments) return [];
    $ids = array_map(fn($c) => (int) $c['id'], $comments);
    $likes = [];
    foreach (rows('SELECT comment_id, user_id FROM comment_likes WHERE comment_id IN (' . marks($ids) . ')', $ids) as $r) {
        $likes[(int) $r['comment_id']][] = (string) $r['user_id'];
    }
    $authorIds = array_values(array_unique(array_map(fn($c) => (int) $c['user_id'], $comments)));
    $authors = [];
    foreach (users_by_ids($authorIds, $viewerId) as $u) $authors[$u['id']] = $u;

    $result = [];
    foreach ($comments as $c) {
        $id = (int) $c['id'];
        $author = (int) $c['user_id'];
        $result[] = [
            'id' => $id,
            'post_id' => (int) $c['post_id'],
            'author_id' => $author,
            'author' => $authors[$author] ?? null,
            'parent_id' => $c['parent_id'] === null ? null : (int) $c['parent_id'],
            'text' => $c['text'],
            'image_url' => media_url($c['image_path']),
            'likes' => $likes[$id] ?? [],
            'created_at' => (int) $c['created_at'],
        ];
    }
    return $result;
}
