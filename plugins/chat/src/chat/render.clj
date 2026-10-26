(ns chat.render
  "MiniMessage templates with <placeholder> tags. Three kinds of values keep
  player-controlled text from becoming formatting:
    :text       inserted as plain text (names, ranks, money, numbers)
    :parsed     parsed as MiniMessage; only for trusted text such as a rank prefix from config
    :component  an Adventure Component inserted as is (the chat message)
  No Bukkit and no plugin state, so it is safe on any thread."
  (:import (net.kyori.adventure.text Component ComponentLike)
           (net.kyori.adventure.text.minimessage MiniMessage)
           (net.kyori.adventure.text.minimessage.tag.resolver Placeholder TagResolver)))

(set! *warn-on-reflection* true)

(defn- resolvers [{:keys [text parsed component]}]
  (concat
   (map (fn [[k v]] (Placeholder/unparsed (name k) (str v))) text)
   (map (fn [[k v]] (Placeholder/parsed (name k) (str v))) parsed)
   (map (fn [[k v]] (Placeholder/component (name k) ^ComponentLike v)) component)))

(defn render
  "Render `template` with the placeholder values (see the ns docstring)."
  ^Component [^String template values]
  (.deserialize (MiniMessage/miniMessage) template
                ^TagResolver (TagResolver/resolver ^Iterable (vec (resolvers values)))))

(defn tps-text
  "TPS as shown to players: one decimal, never above 20."
  [tps]
  (String/format java.util.Locale/ROOT "%.1f" (object-array [(double (min 20.0 (double tps)))])))

(defn count-visible
  "How many of these player ids are not hidden according to (hidden? id)."
  [ids hidden?]
  (count (remove hidden? ids)))

(defn scoreboard-entry
  "A unique, invisible-looking entry name for scoreboard line `i` (0-14).
  The text players see is the score's custom name; this is only a key."
  ^String [i]
  (str "§" (Integer/toHexString (int i)) "§r"))
