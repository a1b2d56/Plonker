package android.net

class TestUri(private val value: String) : Uri() {
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = value.substringBefore("://", "")
    override fun getSchemeSpecificPart(): String = value.substringAfter("://")
    override fun getEncodedSchemeSpecificPart(): String = schemeSpecificPart
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String? = null
    override fun getPort(): Int = -1
    override fun getPath(): String? = null
    override fun getEncodedPath(): String? = null
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = emptyList()
    override fun getLastPathSegment(): String? = null
    override fun buildUpon(): Builder? = null
    override fun toString(): String = value
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) {}
    override fun compareTo(other: Uri): Int = value.compareTo(other.toString())
}
