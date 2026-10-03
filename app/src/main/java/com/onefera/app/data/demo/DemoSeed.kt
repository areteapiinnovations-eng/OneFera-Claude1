package com.onefera.app.data.demo

import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.Comment
import com.onefera.app.data.model.MediaType
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostMedia
import com.onefera.app.data.model.PostType
import com.onefera.app.data.model.PostVisibility
import com.onefera.app.data.model.Story
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.model.extractHashtags
import com.onefera.app.data.model.toSummary

/**
 * Sample creators and content for demo mode, so the app feels alive before Firebase is connected.
 * Images come from picsum.photos and videos from Google's public sample bucket (needs internet).
 */
internal object DemoSeed {
    const val CREATOR_PASSWORD = "creator-demo-only"
    const val VERSION = 2

    private const val HOUR = 60 * 60 * 1000L
    private const val VIDEO_BASE = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample"

    private fun avatar(n: Int) = "https://i.pravatar.cc/300?img=$n"
    private fun photo(seed: String) = "https://picsum.photos/seed/onefera-$seed/1080/1350"
    private fun video(name: String) = PostMedia(
        url = "$VIDEO_BASE/$name.mp4",
        type = MediaType.Video,
        // Poster from picsum so reels look right even before the video has buffered.
        thumbnailUrl = "https://picsum.photos/seed/onefera-reel-$name/1080/1920",
        aspectRatio = 16f / 9f,
    )

    private fun creator(
        uid: String,
        name: String,
        username: String,
        avatar: Int,
        bio: String,
        vibe: String,
        city: String,
        followers: Int,
        aura: Int,
        verified: Boolean = false,
        seller: Boolean = false,
        isPrivate: Boolean = false,
    ) = UserProfile(
        uid = uid,
        displayName = name,
        username = username,
        email = "$username@demo.onefera.app",
        bio = bio,
        avatarUrl = avatar(avatar),
        vibe = vibe,
        city = city,
        birthDate = "2003-01-01",
        isPrivate = isPrivate,
        accountMode = if (seller) AccountMode.Seller else AccountMode.Personal,
        verified = verified,
        auraPoints = aura,
        streakDays = (aura / 60).coerceAtMost(30),
        followersCount = followers,
        followingCount = 120,
        profileViews = followers / 3,
        createdAt = 0L,
    )

    val creators: List<UserProfile> = listOf(
        creator("demo-aanya", "Aanya Rao", "aanya.rao", 47, "thrift queen 🧵 styling budget fits", "Main Character", "Mumbai", 12_400, 910, verified = true),
        creator("demo-kabir", "Kabir Mehta", "kabir.beats", 12, "making noise in Delhi 🎧 new single out", "Night Owl", "Delhi", 8_300, 760, verified = true),
        creator("demo-zoya", "Zoya Khan", "zoya.eats", 5, "eating my way through Hyderabad 🍗", "Soft Life", "Hyderabad", 5_100, 640),
        creator("demo-arjun", "Arjun Nair", "arjun.kicks", 33, "sneaker reseller · drops every Friday 👟", "Grindset", "Bengaluru", 3_900, 580, seller = true),
        creator("demo-meera", "Meera Iyer", "meera.makes", 20, "handmade jewellery 🌸 DM for custom", "Plant Parent", "Chennai", 1_200, 420, isPrivate = true),
        creator("demo-rohan", "Rohan Das", "rohan.games", 59, "Valorant grinder · streams at 9 🎮", "Gamer Mode", "Kolkata", 6_700, 700),
    )

    private val byUid = creators.associateBy { it.uid }
    private fun author(uid: String) = byUid.getValue(uid).toSummary()

    fun posts(now: Long): List<Post> {
        fun post(
            id: String,
            uid: String,
            hoursAgo: Int,
            caption: String,
            media: List<PostMedia>,
            location: String = "",
            likes: Int,
            comments: Int = 0,
            reel: Boolean = false,
        ) = Post(
            id = id,
            authorId = uid,
            author = author(uid),
            type = if (reel) PostType.Reel else PostType.Post,
            caption = caption,
            media = media,
            location = location,
            tags = extractHashtags(caption),
            soundName = if (media.firstOrNull()?.type == MediaType.Video) "Original audio · ${author(uid).username}" else "",
            likeCount = likes,
            commentCount = comments,
            createdAt = now - hoursAgo * HOUR,
            visibility = if (byUid.getValue(uid).isPrivate) PostVisibility.FOLLOWERS else PostVisibility.PUBLIC,
        )
        fun photos(vararg seeds: String) = seeds.map { PostMedia(url = photo(it), aspectRatio = 0.8f) }
        return listOf(
            post("seed-1", "demo-aanya", 1, "Thrifted fit check ✨ the jacket was ₹450, no cap #thrift #ootd #fashion", photos("fit1", "fit2", "fit3"), "Bandra, Mumbai", 1_284, 2),
            post("seed-2", "demo-kabir", 2, "late night studio session 🎧 this one hits different #music #beats", listOf(video("ForBiggerBlazes")), "Hauz Khas, Delhi", 3_402, reel = true),
            post("seed-3", "demo-zoya", 3, "Hyderabad biryani crawl, ranked 🍗 which one's your fav? #food #hyderabad", photos("food1", "food2"), "Old City, Hyderabad", 960),
            post("seed-4", "demo-arjun", 5, "New drop: Neon Kicks Y3K restock this Friday 👟 set your reminders #sneakers #drop", photos("kicks1"), "Indiranagar, Bengaluru", 512),
            post("seed-5", "demo-rohan", 6, "clutch 1v4 on stream last night 😤 #gaming #valorant", listOf(video("ForBiggerEscapes")), "", 2_210, reel = true),
            post("seed-6", "demo-aanya", 9, "GRWM for the college fest 💄 #grwm #fashion", listOf(video("ForBiggerFun")), "", 4_870, reel = true),
            post("seed-7", "demo-zoya", 14, "matcha era 🍵 #cafe #aesthetic", photos("cafe1"), "Jubilee Hills", 744),
            post("seed-8", "demo-kabir", 20, "new single out now 🔊 link in bio #music", photos("studio1"), "", 1_903),
            post("seed-9", "demo-arjun", 26, "unboxing the grail 📦 #sneakers #unboxing", listOf(video("ForBiggerJoyrides")), "", 1_650, reel = true),
            post("seed-10", "demo-meera", 30, "handmade earrings, batch 07 🌸 #handmade #jewellery", photos("craft1", "craft2"), "Chennai", 233),
            post("seed-11", "demo-rohan", 34, "setup tour 💜 rate it 1–10 #setup #gaming", photos("setup1"), "", 1_120),
            post("seed-12", "demo-kabir", 40, "sunset sessions on the terrace 🌇 #music", listOf(video("ForBiggerMeltdowns")), "", 980, reel = true),
        )
    }

    fun comments(now: Long): List<Comment> = listOf(
        Comment("seed-c1", "seed-1", author("demo-kabir"), "the jacket 😍 where from?", now - 50 * 60 * 1000L),
        Comment("seed-c2", "seed-1", author("demo-zoya"), "need this energy fr", now - 30 * 60 * 1000L),
    )

    fun stories(now: Long): List<Story> = listOf(
        Triple("demo-aanya", "s-fit", 2),
        Triple("demo-kabir", "s-studio", 4),
        Triple("demo-zoya", "s-food", 5),
        Triple("demo-arjun", "s-kicks", 7),
        Triple("demo-rohan", "s-setup", 9),
        Triple("demo-aanya", "s-mirror", 1),
    ).mapIndexed { i, (uid, seed, hoursAgo) ->
        val created = now - hoursAgo * HOUR
        Story("seed-story-$i", author(uid), "https://picsum.photos/seed/onefera-$seed/1080/1920", created, created + 24 * HOUR)
    }

    /** Short, friendly comments used to simulate engagement on the user's own posts. */
    val reactions = listOf("this is fire 🔥", "obsessed 😍", "main character energy ✨", "need this asap", "W post", "slay 💅")
}
