(ns auth.password
  "Passwords are never stored: only a salted PBKDF2-HMAC-SHA256 hash of each one, with its salt and
  the number of iterations used (so the cost can go up later without locking anybody out).
  Pure functions on the JDK's own crypto; hashing takes tens of milliseconds, so call it off the
  main thread."
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64)
           (javax.crypto SecretKeyFactory)
           (javax.crypto.spec PBEKeySpec)))

(set! *warn-on-reflection* true)

(def ^:private key-bits 256)
(def ^:private salt-bytes 16)

(defonce ^:private ^SecureRandom random (SecureRandom.))

(defn- b64 ^String [^bytes bs] (.encodeToString (Base64/getEncoder) bs))
(defn- unb64 ^bytes [^String s] (.decode (Base64/getDecoder) s))

(defn- pbkdf2 ^bytes [^String password ^bytes salt iterations]
  (let [spec (PBEKeySpec. (.toCharArray password) salt (int iterations) (int key-bits))]
    (try
      (.getEncoded (.generateSecret (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256") spec))
      (finally (.clearPassword spec)))))

(defn create
  "The record to store for `password`: {:salt :hash :iterations}, salt and hash in Base64. A new
  random salt every time, so two players with the same password have different hashes."
  [^String password iterations]
  (let [salt (byte-array salt-bytes)]
    (.nextBytes random salt)
    {:salt (b64 salt)
     :hash (b64 (pbkdf2 password salt iterations))
     :iterations (long iterations)}))

(defn verify
  "Whether `password` is the one behind the stored record. The comparison takes the same time
  wherever the hashes differ."
  [^String password {:keys [salt hash iterations]}]
  (boolean
   (and password salt hash iterations
        (MessageDigest/isEqual (pbkdf2 password (unb64 salt) iterations)
                               (unb64 hash)))))
