(ns economy.ledger
  "Balance arithmetic with no Bukkit in it. Amounts are whole numbers (longs);
  every function returns {:ok value} or {:error reason}, never throws on bad
  input, and refuses anything that would overflow."
  (:import (java.util Locale)))

(set! *warn-on-reflection* true)

(defn parse-amount
  "A positive whole number from text, or nil. Digits only: no sign, no decimals."
  [^String s]
  (when (and s (re-matches #"[0-9]{1,18}" s))
    (let [n (Long/parseLong s)]
      (when (pos? n) n))))

(defn deposit
  "{:ok new-balance} after adding `amount` to `balance`, or {:error :invalid}
  / {:error :overflow} when the result would pass `max-balance`."
  [balance amount max-balance]
  (cond
    (not (pos? (long amount))) {:error :invalid}
    (> (long amount) (- (long max-balance) (long balance))) {:error :overflow}
    :else {:ok (+ (long balance) (long amount))}))

(defn withdraw
  "{:ok new-balance} after taking `amount`, or {:error :invalid} /
  {:error :insufficient}."
  [balance amount]
  (cond
    (not (pos? (long amount))) {:error :invalid}
    (> (long amount) (long balance)) {:error :insufficient}
    :else {:ok (- (long balance) (long amount))}))

(defn transfer
  "Move `amount` from one balance to another. {:ok {:from n :to n}} or an error
  from withdraw (:insufficient) / deposit (:overflow, reported as :overflow)."
  [from-balance to-balance amount max-balance]
  (let [w (withdraw from-balance amount)]
    (if (:error w)
      w
      (let [d (deposit to-balance amount max-balance)]
        (if (:error d)
          d
          {:ok {:from (:ok w) :to (:ok d)}})))))

(defn format-number
  "Group digits the Brazilian way: 1250000 -> \"1.250.000\"."
  [n]
  (String/format (Locale/forLanguageTag "pt-BR") "%,d" (object-array [(long n)])))

(defn format-amount
  "Symbol and grouped number: (format-amount \"$\" 1250) -> \"$1.250\"."
  [^String symbol n]
  (str symbol (format-number n)))
