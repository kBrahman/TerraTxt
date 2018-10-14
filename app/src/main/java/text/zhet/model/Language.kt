package text.zhet.model

data class Language(var name: String, var code: String) {

    override fun toString(): String {
        return name
    }
}