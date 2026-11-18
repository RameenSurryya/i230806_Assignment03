-- Kinnect (Assignment 3) MySQL schema: every table the app needs, created once.
-- Times are BIGINT milliseconds since 1970 (the same numbers Android uses).
-- Media columns hold a path under uploads/ (e.g. "uploads/posts/ab12.jpg"); the API turns it into a URL.

SET NAMES utf8mb4;

-- ===== Accounts =====
CREATE TABLE IF NOT EXISTS users (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  email         VARCHAR(190) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,          -- password_hash() in PHP, never the password
  name          VARCHAR(100) NOT NULL DEFAULT '',
  username      VARCHAR(30)  NULL UNIQUE,        -- unique: two people can't pick the same one
  bio           VARCHAR(300) NOT NULL DEFAULT '',
  website       VARCHAR(200) NOT NULL DEFAULT '',
  work          VARCHAR(100) NOT NULL DEFAULT '',
  study         VARCHAR(100) NOT NULL DEFAULT '',
  city          VARCHAR(100) NOT NULL DEFAULT '',
  phone         VARCHAR(30)  NOT NULL DEFAULT '',
  gender        VARCHAR(20)  NOT NULL DEFAULT '',
  birthday      VARCHAR(20)  NOT NULL DEFAULT '',
  photo_path    VARCHAR(255) NULL,
  cover_path    VARCHAR(255) NULL,
  setup_done    TINYINT(1)   NOT NULL DEFAULT 0, -- 0 = send to Edit profile after login
  is_online     TINYINT(1)   NOT NULL DEFAULT 0,
  last_seen     BIGINT       NOT NULL DEFAULT 0,
  fcm_token     VARCHAR(255) NULL,
  created_at    BIGINT       NOT NULL,
  INDEX idx_users_name (name),
  INDEX idx_users_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per login. Only a hash of the token is stored; logout sets revoked = 1.
CREATE TABLE IF NOT EXISTS sessions (
  token_hash CHAR(64) PRIMARY KEY,
  user_id    BIGINT  NOT NULL,
  created_at BIGINT  NOT NULL,
  revoked    TINYINT(1) NOT NULL DEFAULT 0,
  INDEX idx_sessions_user (user_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ===== Friends =====
CREATE TABLE IF NOT EXISTS friend_requests (
  from_id    BIGINT NOT NULL,
  to_id      BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (from_id, to_id),
  INDEX idx_requests_to (to_id),
  FOREIGN KEY (from_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (to_id)   REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Stored both ways (A->B and B->A), so "my friends" is one simple query.
CREATE TABLE IF NOT EXISTS friends (
  user_id    BIGINT NOT NULL,
  friend_id  BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (user_id, friend_id),
  FOREIGN KEY (user_id)   REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (friend_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS close_friends (
  user_id   BIGINT NOT NULL,
  friend_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, friend_id),
  FOREIGN KEY (user_id)   REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (friend_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ===== Posts =====
CREATE TABLE IF NOT EXISTS posts (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT NOT NULL,
  text        TEXT   NOT NULL,
  share_count INT    NOT NULL DEFAULT 0,
  created_at  BIGINT NOT NULL,
  INDEX idx_posts_user_time (user_id, created_at),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS post_images (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  post_id  BIGINT NOT NULL,
  path     VARCHAR(255) NOT NULL,
  position INT NOT NULL,
  FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- One reaction per user per post: the primary key makes a second one replace the first.
CREATE TABLE IF NOT EXISTS reactions (
  post_id    BIGINT NOT NULL,
  user_id    BIGINT NOT NULL,
  type       VARCHAR(10) NOT NULL,             -- like, love, haha, wow, sad, angry
  created_at BIGINT NOT NULL,
  PRIMARY KEY (post_id, user_id),
  FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS comments (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  post_id    BIGINT NOT NULL,
  user_id    BIGINT NOT NULL,
  parent_id  BIGINT NULL,                       -- set for a reply
  text       TEXT   NOT NULL,
  image_path VARCHAR(255) NULL,                 -- image comment
  created_at BIGINT NOT NULL,
  INDEX idx_comments_post (post_id, created_at),
  FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS comment_likes (
  comment_id BIGINT NOT NULL,
  user_id    BIGINT NOT NULL,
  PRIMARY KEY (comment_id, user_id),
  FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id)    REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ===== Stories (the API only returns rows with expires_at in the future) =====
CREATE TABLE IF NOT EXISTS stories (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  media_path VARCHAR(255) NOT NULL,
  media_type VARCHAR(10)  NOT NULL,             -- image or video
  label      VARCHAR(100) NOT NULL DEFAULT '',
  audience   VARCHAR(20)  NOT NULL DEFAULT 'public',  -- public or close_friends
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,                   -- created_at + 24 hours
  INDEX idx_stories_user (user_id, expires_at),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS story_views (
  story_id  BIGINT NOT NULL,
  viewer_id BIGINT NOT NULL,
  viewed_at BIGINT NOT NULL,
  reaction  VARCHAR(10) NULL,
  PRIMARY KEY (story_id, viewer_id),
  FOREIGN KEY (story_id)  REFERENCES stories(id) ON DELETE CASCADE,
  FOREIGN KEY (viewer_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ===== Chat (chat_id = smaller user id + "_" + bigger user id) =====
CREATE TABLE IF NOT EXISTS messages (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  chat_id     VARCHAR(50)  NOT NULL,
  sender_id   BIGINT NOT NULL,
  receiver_id BIGINT NOT NULL,
  type        VARCHAR(10)  NOT NULL,            -- text, image, video, file, post, system
  text        TEXT         NOT NULL,
  media_path  VARCHAR(255) NULL,
  file_name   VARCHAR(255) NULL,
  file_size   BIGINT       NULL,
  post_id     BIGINT       NULL,
  reply_to_id BIGINT       NULL,
  edited      TINYINT(1)   NOT NULL DEFAULT 0,
  deleted     TINYINT(1)   NOT NULL DEFAULT 0,
  created_at  BIGINT       NOT NULL,
  updated_at  BIGINT       NOT NULL,            -- changes on edit / delete / reaction, so the app can fetch only what changed
  INDEX idx_messages_chat (chat_id, id),
  INDEX idx_messages_updated (chat_id, updated_at),
  FOREIGN KEY (sender_id)   REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (receiver_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS message_reactions (
  message_id BIGINT NOT NULL,
  user_id    BIGINT NOT NULL,
  type       VARCHAR(10) NOT NULL,
  PRIMARY KEY (message_id, user_id),
  FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- When each person last opened a chat ("Seen") and until when they are typing.
CREATE TABLE IF NOT EXISTS chat_members (
  chat_id      VARCHAR(50) NOT NULL,
  user_id      BIGINT NOT NULL,
  read_at      BIGINT NOT NULL DEFAULT 0,
  typing_until BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (chat_id, user_id)
) ENGINE=InnoDB;

-- ===== Notifications (rows of screen 18) =====
CREATE TABLE IF NOT EXISTS notifications (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT NOT NULL,                   -- who sees it
  from_id    BIGINT NOT NULL,                   -- who caused it
  type       VARCHAR(30) NOT NULL,
  post_id    BIGINT NULL,
  text       VARCHAR(300) NOT NULL DEFAULT '',
  is_read    TINYINT(1) NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  INDEX idx_notifications_user (user_id, created_at),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (from_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== Calls (also the call log) =====
CREATE TABLE IF NOT EXISTS calls (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  caller_id   BIGINT NOT NULL,
  callee_id   BIGINT NOT NULL,
  channel     VARCHAR(64) NOT NULL,             -- Agora channel both phones join
  type        VARCHAR(10) NOT NULL,             -- voice or video
  status      VARCHAR(20) NOT NULL,             -- ringing, accepted, declined, missed, ended
  created_at  BIGINT NOT NULL,
  answered_at BIGINT NULL,
  ended_at    BIGINT NULL,
  INDEX idx_calls_callee (callee_id, status),
  INDEX idx_calls_caller (caller_id),
  FOREIGN KEY (caller_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (callee_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ===== Marketplace =====
CREATE TABLE IF NOT EXISTS listings (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT NULL,
  title      VARCHAR(100) NOT NULL,
  price      VARCHAR(30)  NOT NULL,
  place      VARCHAR(100) NOT NULL DEFAULT '',
  photo      VARCHAR(20)  NOT NULL DEFAULT 'sage',  -- name of a placeholder picture in the app
  created_at BIGINT NOT NULL,
  INDEX idx_listings_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== No duplicates from offline sync =====
-- Every queued action from the phone carries a UUID (X-Request-Id). The first time it arrives the
-- action runs and its answer is saved here; if the same UUID arrives again (a retry after a
-- dropped connection) the saved answer is returned and nothing runs twice.
CREATE TABLE IF NOT EXISTS processed_requests (
  request_id CHAR(36) PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  response   MEDIUMTEXT NOT NULL,
  created_at BIGINT NOT NULL
) ENGINE=InnoDB;