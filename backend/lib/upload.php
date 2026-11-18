<?php
// Saving uploaded files to the server's disk (uploads/<folder>/<random name>) and turning a
// stored path back into a URL the app can load.
declare(strict_types=1);

const MAX_UPLOAD_BYTES = 25 * 1024 * 1024;   // 25 MB

const IMAGE_TYPES = ['jpg', 'jpeg', 'png', 'webp', 'gif'];
const VIDEO_TYPES = ['mp4', '3gp', 'webm', 'mkv'];
const FILE_TYPES  = ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'zip', 'mp3', 'm4a'];

/** "image", "video" or "file" from the file's extension; null if it isn't allowed. */
function kind_of(string $extension): ?string
{
    if (in_array($extension, IMAGE_TYPES, true)) return 'image';
    if (in_array($extension, VIDEO_TYPES, true)) return 'video';
    if (in_array($extension, FILE_TYPES, true)) return 'file';
    return null;
}

/** Moves one uploaded file into uploads/<folder>/. Returns path, kind, original name and size. */
function store_file(array $file, string $folder, array $kinds): array
{
    if ($file['error'] !== UPLOAD_ERR_OK) fail('Upload failed (code ' . $file['error'] . ')');
    if ($file['size'] > MAX_UPLOAD_BYTES) fail('File is larger than 25 MB', 413);

    $extension = strtolower(pathinfo($file['name'], PATHINFO_EXTENSION));
    $kind = kind_of($extension);
    if ($kind === null || !in_array($kind, $kinds, true)) fail('This type of file is not allowed');

    // A random name: no clashes, and nobody can guess other people's file names.
    $name = bin2hex(random_bytes(16)) . '.' . $extension;
    $dir = __DIR__ . '/../uploads/' . $folder;
    if (!is_dir($dir)) mkdir($dir, 0775, true);
    if (!move_uploaded_file($file['tmp_name'], "$dir/$name")) fail('Could not save the file', 500);

    return [
        'path' => "uploads/$folder/$name",
        'kind' => $kind,
        'name' => basename($file['name']),
        'size' => (int) $file['size'],
    ];
}

/** The single file sent in multipart field [field], or null when none was sent. */
function upload(string $field, string $folder, array $kinds = ['image']): ?array
{
    if (empty($_FILES[$field]) || $_FILES[$field]['error'] === UPLOAD_ERR_NO_FILE) return null;
    return store_file($_FILES[$field], $folder, $kinds);
}

/** Every file sent as field[] (e.g. the photos of a post), in order. */
function uploads(string $field, string $folder, array $kinds = ['image']): array
{
    if (empty($_FILES[$field]) || !is_array($_FILES[$field]['name'])) return [];
    $saved = [];
    foreach ($_FILES[$field]['name'] as $i => $name) {
        $saved[] = store_file([
            'name' => $name,
            'tmp_name' => $_FILES[$field]['tmp_name'][$i],
            'error' => $_FILES[$field]['error'][$i],
            'size' => $_FILES[$field]['size'][$i],
        ], $folder, $kinds);
    }
    return $saved;
}

/** Deletes a stored file (when its post, story or message is deleted). */
function delete_file(?string $path): void
{
    if ($path && str_starts_with($path, 'uploads/')) @unlink(__DIR__ . '/../' . $path);
}

/** "uploads/posts/ab.jpg" -> "http://192.168.1.5/kinnect/uploads/posts/ab.jpg" (the host the phone used). */
function media_url(?string $path): ?string
{
    if (!$path) return null;
    $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
    return $scheme . '://' . $_SERVER['HTTP_HOST'] . config()['base_path'] . '/' . $path;
}