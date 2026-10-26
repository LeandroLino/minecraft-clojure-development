(ns chat.death
  "The server's own death messages: what killed the player picks a category, the config holds a few
  variations of each, and one is drawn at random. Pure functions (the random number is passed in),
  so they are tested without a server. Names go in as plain text through chat.render, so a player
  called <red>Admin cannot turn their own name into formatting."
  (:require [chat.render :as render]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def categories
  "Every category the config may have messages for."
  [:fall :lava :fire :drown :void :explosion :cactus :starve :magic :freeze :pvp :mob :generic])

(def ^:private damage-type->category
  "Minecraft damage type (the part of its key after minecraft:) -> category."
  {"fall" :fall "ender_pearl" :fall "fly_into_wall" :fall
   "lava" :lava
   "in_fire" :fire "on_fire" :fire "campfire" :fire "hot_floor" :fire "lightning_bolt" :fire
   "drown" :drown
   "out_of_world" :void "generic_kill" :void
   "explosion" :explosion "player_explosion" :explosion "fireworks" :explosion
   "cactus" :cactus "sweet_berry_bush" :cactus "stalagmite" :cactus
   "starve" :starve
   "magic" :magic "indirect_magic" :magic "wither" :magic "dragon_breath" :magic
   "freeze" :freeze})

(defn category
  "The category of a death. `damage-type` is the damage type's key (\"fall\", \"lava\", ...), `killer`
  is :player when a player was behind it, :mob when some other creature was, nil otherwise. Somebody
  behind the death wins over what the damage was."
  [damage-type killer]
  (case killer
    :player :pvp
    :mob :mob
    (get damage-type->category (some-> damage-type str str/lower-case) :generic)))

(defn choose
  "One of the `variations` (a non-empty sequence). `rand-n` is (fn [n]) giving an integer in [0, n);
  it is a parameter so a test can pick on purpose."
  [variations rand-n]
  (let [v (vec variations)]
    (nth v (rand-n (count v)))))

(defn template
  "The text to use for a death of `category`: one of the configured variations, or `fallback` when
  the category has none. `messages` is the config's death.messages (string keys)."
  [messages category fallback rand-n]
  (let [variations (get messages (name category))]
    (if (and (sequential? variations) (seq variations))
      (choose variations rand-n)
      fallback)))

(defn message
  "The death message as a Component: the drawn template with <player> and <killer> filled in as plain
  text (killer may be nil, when the category has none)."
  [messages category fallback player-name killer-name rand-n]
  (render/render (template messages category fallback rand-n)
                 {:text (cond-> {:player player-name}
                          killer-name (assoc :killer killer-name))}))
