;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljscm.reader)

(cljscm.core/add-protocol-hints!
 {cljscm.reader/IPositioned [cljscm.reader/StringPushbackReader cljscm.reader/PortPushbackReader]})

(defprotocol PushbackReader
  (-read-char [reader] "Returns the next char from the Reader,
nil if the end of stream has been reached")
  (-unread [reader ch] "Push back a single character on to the stream"))

(defprotocol IPositioned
  (-line [reader] "returns reader's current line number")
  (-column [reader] "returns reader's current column number"))

(defn line [reader]
  (when (satisfies? IPositioned reader)
    (-line reader)))

(defn column [reader]
  (when (satisfies? IPositioned reader)
    (-column reader)))

(defn read-char [reader] (-read-char reader))
(defn unread [reader ch] (-unread reader ch))

; Using two atoms is less idomatic, but saves the repeat overhead of map creation
(deftype StringPushbackReader [s index-atom buffer-atom]
  PushbackReader
  (-read-char [reader]
             (if (empty? @buffer-atom)
               (let [idx @index-atom]
                 (swap! index-atom inc)
                 (when (< idx ((scm* {} string-length) s))
                   ((scm* {} string-ref) s idx)))
               (let [buf @buffer-atom]
                 (swap! buffer-atom rest)
                 (first buf))))
  (-unread [reader ch] (swap! buffer-atom #(cons ch %)))
  IPositioned
  (-line [r] 1)
  (-column [r] (- @index-atom (count @buffer-atom))))

(defn push-back-reader [s]
  "Creates a StringPushbackReader from a given string"
  (StringPushbackReader. s (atom 0) (atom nil)))

(deftype PortPushbackReader [port buffer-atom]
  PushbackReader
  (-read-char [reader]
    (if (empty? @buffer-atom)
      (let [ch ((scm* {} read-char) port)]
        (when-not ((scm* {} eof-object?) ch) ch))
      (let [buf @buffer-atom]
        (swap! buffer-atom rest)
        (first buf))))
  (-unread [reader ch] (swap! buffer-atom #(cons ch %)))
  IPositioned
  (-line [r] ((scm* {} input-port-line) port))
  (-column [r] ((scm* {} input-port-column) port)))

(defn port-push-back-reader [port]
  "Creates a PortPushbackReader from a given scheme port"
  (PortPushbackReader. port (atom nil)))

(defn file-reader [filename]
  (port-push-back-reader ((scm* {} open-input-file) filename)))

;FIXME: analyze not resolving protocols.
(defn -toString [x] (cljscm.core/-toString x))
(defn write [sb o]
  (cljscm.core/-write sb (str o))
  sb)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ^boolean whitespace?
  "Checks whether a given character is whitespace"
  [ch]
  (contains? #{\, \space \tab \newline \return} ch))

(defn- ^boolean numeric?
  "Checks whether a given character is numeric"
  [ch]
  (<= 48 (int ch) 57))

(defn- ^boolean comment-prefix?
  "Checks whether the character begins a comment."
  [ch]
  (identical? \; ch))

(defn- ^boolean number-literal?
  "Checks whether the reader is at the start of a number literal"
  [reader initch]
  (or (numeric? initch)
      (and (or (identical? \+ initch) (identical? \- initch))
           (numeric? (let [next-ch (read-char reader)]
                       (unread reader next-ch)
                       next-ch)))))

(declare read macros dispatch-macros)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; read helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


; later will do e.g. line numbers...
(defn reader-error
  [rdr & msg]
  (throw (Error. (apply str msg))))

(defn ^boolean macro-terminating? [ch]
  (and (not (identical? ch \#))
       (not (identical? ch \'))
       (not (identical? ch \:))
       (macros ch)))

(defn read-token
  [rdr initch]
  (loop [sb (write (string-buffer-writer) initch)
         ch (read-char rdr)]
    (if (or (nil? ch)
            (whitespace? ch)
            (macro-terminating? ch))
      (do (unread rdr ch) (-toString sb))
      (recur (write sb ch) (read-char rdr)))))

(defn read-char* [rdr _]
  (let [tok (read-token rdr (-read-char rdr))]
    (cond
      (= tok "space") \space
      (= tok "tab") \tab
      (= tok "newline") \newline
      (= tok "return") \return
      (= (count tok) 1) (first tok)
      :else (reader-error rdr "Uknown character token: " tok))))

(defn skip-line
  "Advances the reader to the end of a line. Returns the reader"
  [reader _]
  (loop []
    (let [ch (read-char reader)]
      (if (or (identical? ch \newline) (identical? ch \return) (nil? ch))
        reader
        (recur)))))

#_( TODO (defn- re-find*
  [re s]
  (let [matches (.exec re s)]
    (when-not (nil? matches)
      (if (== (alength matches) 1)
        (aget matches 0)
        matches)))))

#_( TODO (defn- re-matches*
                [re s]
                (let [matches (.exec re s)]
                  (when (and (not (nil? matches))
                             (identical? (aget matches 0) s))
                    (if (== (alength matches) 1)
                      (aget matches 0)
                      matches)))))

(defn escape-char-map [c]
  (cond
   (identical? c \t) "\t"
   (identical? c \r) "\r"
   (identical? c \n) "\n"
   (identical? c \\) \\
   (identical? c \") \"
   (identical? c \b) "\b"
   (identical? c \f) "\f"
   :else nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; unicode
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn read-2-chars [reader]
  (-toString
   (-> (string-buffer-writer)
       (write (read-char reader))
       (write (read-char reader)))))

(defn read-4-chars [reader]
  (-toString
   (-> (string-buffer-writer)
       (write (read-char reader))
       (write (read-char reader))
       (write (read-char reader))
       (write (read-char reader)))))

;(def unicode-2-pattern (re-pattern "[0-9A-Fa-f]{2}"))
;(def unicode-4-pattern (re-pattern "[0-9A-Fa-f]{4}"))

#_( TODO (defn validate-unicode-escape [unicode-pattern reader escape-char unicode-str]
  (if (re-matches unicode-pattern unicode-str)
    unicode-str
    (reader-error reader "Unexpected unicode escape \\" escape-char unicode-str))))

#_( TODO (defn make-unicode-char [code-str]
    (let [code (js/parseInt code-str 16)]
      (.fromCharCode js/String code))))

(defn escape-char
  [buffer reader]
  (let [ch (read-char reader)
        mapresult (escape-char-map ch)]
    (if mapresult
      mapresult
      (cond
        #_( TODO (identical? ch \x)
                 (->> (read-2-chars reader)
                      (validate-unicode-escape unicode-2-pattern reader ch)
                      (make-unicode-char))

                 (identical? ch \u)
                 (->> (read-4-chars reader)
                      (validate-unicode-escape unicode-4-pattern reader ch)
                      (make-unicode-char)))

        (numeric? ch)
        (scm* {::ch ch} (integer->char ::ch))

        :else
        (reader-error reader "Unexpected unicode escape \\" ch )))))

(defn read-past
  "Read until first character that doesn't match pred, returning
   char."
  [pred rdr]
  (loop [ch (read-char rdr)]
    (if (pred ch)
      (recur (read-char rdr))
      ch)))

(defn read-delimited-list
  [delim rdr recursive?]
  (loop [a (transient [])]
    (let [ch (read-past whitespace? rdr)]
      (when-not ch (reader-error rdr "EOF while reading"))
      (cond
        (identical? delim ch) (persistent! a)
        (comment-prefix? ch) (do (read-comment rdr ch) (recur a))
        :else (if-let [macrofn (macros ch)]
                (let [mret (macrofn rdr ch)]
                  (recur (if (identical? mret rdr) a (conj! a mret))))
                (do
                  (unread rdr ch)
                  (let [o (read rdr true nil recursive?)]
                    (recur (if (identical? o rdr) a (conj! a o))))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; data structure readers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn not-implemented
  [rdr ch]
  (reader-error rdr "Reader for " ch " not implemented yet"))

(declare maybe-read-tagged-type)

(defn read-dispatch
  [rdr _]
  (let [ch (read-char rdr)
        dm (dispatch-macros ch)]
    (if dm
      (dm rdr nil)
      (if-let [obj (maybe-read-tagged-type rdr ch)]
        obj
        (reader-error rdr "No dispatch macro for " ch)))))

(defn read-unmatched-delimiter
  [rdr ch]
  (reader-error rdr "Unmached delimiter " ch))

(defn read-list
  [rdr _]
  (apply list (read-delimited-list \) rdr true)))

(def read-comment skip-line)

(defn read-vector
  [rdr _]
  (read-delimited-list \] rdr true))

(defn read-map
  [rdr _]
  (let [l (read-delimited-list \} rdr true)]
    (when (odd? (count l))
      (reader-error rdr "Map literal must contain an even number of forms"))
    (apply hash-map l)))

(defn read-number
  [reader initch]
  (loop [buffer (write (string-buffer-writer) initch)
         ch (read-char reader)]
    (if (or (nil? ch) (whitespace? ch) (macros ch))
      (do
        (unread reader ch)
        (let [s (-toString buffer)]
          (or (scm* {::s s ::init :init}
                    (car (call-with-input-string (list ::init ::s) read-all)))
              (reader-error reader "Invalid number format [" s "]"))))
      (recur (write buffer ch) (read-char reader)))))

(defn read-string*
  [reader _]
  (loop [buffer (string-buffer-writer)
         ch (read-char reader)]
    (cond
     (nil? ch) (reader-error reader "EOF while reading")
     (identical? \\ ch) (recur (do (write buffer (escape-char buffer reader)) buffer)
                        (read-char reader))
     (identical? \" ch) (-toString buffer)
     :default (recur (do (write buffer ch) buffer) (read-char reader)))))

(defn special-symbols [t not-found]
  (cond
   (= t "nil") nil
   (= t "true") true
   (= t "false") false
   :else not-found))

(defn read-symbol
  [reader initch]
  (let [token (read-token reader initch)]
    (if (some #{\/} token)
      (let [[namespace _ name] (partition-by #{\/} token)]
        (symbol (apply str namespace)
                (apply str name)))
      (special-symbols token (symbol token)))))

(defn read-keyword
  [reader initch]
  (let [token (read-token reader (read-char reader))
        [resolve? tok-chars] (if (= \: (first token))
                               [true (rest token)]
                               [false (seq token)])
        [ns-chars _ name-chars] (if (some #{\/} tok-chars)
                                  (partition-by #{\/} tok-chars)
                                  [nil nil tok-chars])
        sym (symbol (and (seq ns-chars) (apply str ns-chars))
                    (apply str name-chars))]
    #_"TODO: Invalid keyword checking, disallow further ::'s etc."
    (keyword (if resolve?
               (:name (resolve sym))
               sym))))

(defn desugar-meta
  [f]
  (cond
   (symbol? f) {:tag f}
   (string? f) {:tag f}
   (keyword? f) {f true}
   :else f))

(defn wrapping-reader
  [sym]
  (fn [rdr _]
    (list sym (read rdr true nil true))))

(defn throwing-reader
  [msg]
  (fn [rdr _]
    (reader-error rdr msg)))

(defn read-meta
  [rdr _]
  (let [m (desugar-meta (read rdr true nil true))]
    (when-not (map? m)
      (reader-error rdr "Metadata must be Symbol,Keyword,String or Map"))
    (let [o (read rdr true nil true)]
      (if (satisfies? IWithMeta o)
        (with-meta o (merge (meta o) m))
        (reader-error rdr "Metadata can only be applied to IWithMetas")))))

(defn read-set
  [rdr _]
  (set (read-delimited-list \} rdr true)))

(defn read-regex
  "TODO"
  [rdr ch]
  (list 'cljscm.core/re-pattern (read-string* rdr ch)))

(defn read-discard
  [rdr _]
  (read rdr true nil true)
  rdr)

(defn expand-list [lst gensym-env]
  (cons 'cljscm.core/concat
        (map (fn [item]
               (if (and (seq? item) (= 'unquote-splicing (first item)))
                 (second item)
                 (list 'cljscm.core/list (syntax-quote* item gensym-env)))) lst)))

(defn syntax-quote* [o gensym-env]
  (let [ret
        (cond
          (symbol? o) (list 'quote
                            (cond (= \# (last (str o)))
                                  , (-> (swap! gensym-env update-in [o]
                                               #(or % (gensym o)))
                                        (get o))
                                    (special-symbol? o) o
                                    :else (:name (resolve o))))
          (and (seq? o) (= 'unquote (first o))) (second o)
          (and (seq? o) (= 'unquote-splicing (first o))) (throw (Exception. "splice not in list"))
          (map? o) (list 'cljscm.core/apply 'cljscm.core/hash-map
                         (expand-list (apply concat o) gensym-env))
          (vector? o) (list 'cljscm.core/apply 'cljscm.core/vector (expand-list o gensym-env))
          (set? o) (list 'cljscm.core/apply 'cljscm.core/hash-set (expand-list o gensym-env))
          (seq? o) (if (seq o) (expand-list o gensym-env), (list 'cljscm.core/list))
          :else o)]
    (if (meta o)
      (list 'cljscm.core/with-meta ret
            (syntax-quote* (meta o) gensym-env))
      ret)))

(defn read-syntax-quote [rdr _]
  (let [form (read rdr true nil false)]
    (syntax-quote* form (atom {}))))

(defn read-unquote-or-splice [rdr _]
  (let [next-char (read-char rdr)]
    (if (= next-char \@)
      (list 'unquote-splicing (read rdr true nil false))
      (do
        (unread rdr next-char)
        (list 'unquote (read rdr true nil false))))))

(def ^:dynamic *fn-arg-env* nil)

(defn register-arg
  "-1 for 'rest', 1 for 1st arg (0 not used)"
  [n]
  (or (get @*fn-arg-env* n)
      (let [sym (gensym (if (= -1 n)
                          "rest__"
                          (str "p" n "__")))]
        (do (swap! *fn-arg-env* assoc n sym)
            sym))))

(defn read-fn [rdr _]
  (if *fn-arg-env*
    (reader-error rdr "Nested #()'s are not allowed")
    (binding [*fn-arg-env* (atom {})]
      (let [body (read-delimited-list \) rdr true)
            max-idx (apply max (cons 0 (keys @*fn-arg-env*)))
            rest? (get @*fn-arg-env* -1)]
        (doall
         (list 'fn*
               (vec (concat (map #(register-arg (inc %)) (range max-idx))
                            (when rest? ['& (register-arg -1)]) ))
               (apply list body)))))))

(defn read-arg [rdr initch]
  (if *fn-arg-env*
    (let [none ['none]
          next-char (read-char rdr)
          num (- (int next-char) 48)]
      (cond
        (< 0 num 10) (register-arg num)
        (= \& next-char) (register-arg -1)
        :else (do (unread rdr next-char)
                  (register-arg 1))))
    (read-symbol rdr initch)))

(defn macros [c]
  (cond
   (identical? c \") read-string*
   (identical? c \:) read-keyword
   (identical? c \;) not-implemented ;; never hit this
   (identical? c \') (wrapping-reader 'quote)
   (identical? c \@) (wrapping-reader 'deref)
   (identical? c \^) read-meta
   (identical? c \`) read-syntax-quote
   (identical? c \~) read-unquote-or-splice
   (identical? c \() read-list
   (identical? c \)) read-unmatched-delimiter
   (identical? c \[) read-vector
   (identical? c \]) read-unmatched-delimiter
   (identical? c \{) read-map
   (identical? c \}) read-unmatched-delimiter
   (identical? c \\) read-char*
   (identical? c \%) read-arg
   (identical? c \#) read-dispatch
   :else nil))

;; omitted by design: var reader, eval reader
(defn dispatch-macros [s]
  (cond
   (identical? s \{) read-set
   (identical? s \<) (throwing-reader "Unreadable form")
   (identical? s \") read-regex
   (identical? s\!) read-comment
   (identical? s \_) read-discard
   (identical? s \() read-fn
   :else nil))

(defn read
  "Reads the first object from a PushbackReader. Returns the object read.
   If EOF, throws if eof-is-error is true. Otherwise returns sentinel."
  [reader eof-is-error sentinel is-recursive]
  (let [ln (line reader)
        col (column reader)
        ch (read-char reader)]
    (cond
      (nil? ch) (if eof-is-error (reader-error reader "EOF while reading") sentinel)
      (whitespace? ch) (recur reader eof-is-error sentinel is-recursive)
      (comment-prefix? ch) (recur (read-comment reader ch) eof-is-error sentinel is-recursive)
      :else (let [f (macros ch)
                  res
                  (cond
                    f (f reader ch)
                    (number-literal? reader ch) (read-number reader ch)
                    :else (read-symbol reader ch))]
              (if (identical? res reader)
                (recur reader eof-is-error sentinel is-recursive)
                (if (satisfies? IWithMeta res)
                  (let [res (if ln (vary-meta res assoc :line ln) res)
                        res (if col (vary-meta res assoc :column col) res)]
                    res)
                  res))))))

(defn read-string
  "Reads one object from the string s"
  [s]
  (let [r (push-back-reader s)]
    (read r true nil false)))

(defn file-seq-reader
  "Seq of forms in a Clojure or ClojureScript file."
  ([f] (file-seq-reader f (file-reader f)))
  ([f reader]
     (let [eof (list 'eof)
           r (read reader false eof false)]
       (if (identical? r eof)
         (do ((scm* {} close-port) (.-port reader))
             nil)
         (lazy-seq
           (cons r
                 (file-seq-reader f reader)))))))


;; read instances

(defn ^:private zero-fill-right [s width]
  (cond (= width (count s)) s
        (< width (count s)) (subs s 0 width)
        :else (loop [b (write (string-buffer-writer) s)]
                (if (< (count b) width)
                  (recur (write b \0))
                  (-toString b)))))

(defn ^:private divisible?
  [num div]
  (zero? (mod num div)))

(defn ^:private indivisible?
  [num div]
    (not (divisible? num div)))

(defn ^:private leap-year?
  [year]
  (and (divisible? year 4)
       (or (indivisible? year 100)
           (divisible? year 400))))

(def ^:private days-in-month
  (let [dim-norm [nil 31 28 31 30 31 30 31 31 30 31 30 31]
        dim-leap [nil 31 29 31 30 31 30 31 31 30 31 30 31]]
    (fn [month leap-year?]
      (get (if leap-year? dim-leap dim-norm) month))))

#_( TODO (def ^:private parse-and-validate-timestamp
  (let [timestamp ;#"(\d\d\d\d)(?:-(\d\d)(?:-(\d\d)(?:[T](\d\d)(?::(\d\d)(?::(\d\d)(?:[.](\d+))?)?)?)?)?)?(?:[Z]|([-+])(\d\d):(\d\d))?"
        check (fn [low n high msg]
                (assert (<= low n high) (str msg " Failed:  " low "<=" n "<=" high))
                n)]
    (fn [ts]
      (when-let [[[_ years months days hours minutes seconds milliseconds] [_ _ _] :as V]
                 (->> ts
                      (re-matches timestamp)
                      (split-at 8)
                      (map vec))]
        (let [[[_ y mo d h m s ms] [offset-sign offset-hours offset-minutes]]
              (->> V
                   (map #(update-in %2 [0] %)
                        [(constantly nil) #(if (= % "-") "-1" "1")])
                   (map (fn [v] (map #(js/parseInt % 10) v))))
              offset (* offset-sign (+ (* offset-hours 60) offset-minutes))]
          [(if-not years 1970 y)
           (if-not months 1        (check 1 mo 12 "timestamp month field must be in range 1..12"))
           (if-not days 1          (check 1 d (days-in-month mo (leap-year? y)) "timestamp day field must be in range 1..last day in month"))
           (if-not hours 0         (check 0 h 23 "timestamp hour field must be in range 0..23"))
           (if-not minutes 0       (check 0 m 59 "timestamp minute field must be in range 0..59"))
           (if-not seconds 0       (check 0 s (if (= m 59) 60 59) "timestamp second field must be in range 0..60"))
           (if-not milliseconds 0  (check 0 ms 999 "timestamp millisecond field must be in range 0..999"))
           offset]))))))

#_( TODO (defn parse-timestamp
  [ts]
  (if-let [[years months days hours minutes seconds ms offset]
           (parse-and-validate-timestamp ts)]
    (js/Date.
     (- (.UTC js/Date years (dec months) days hours minutes seconds ms)
        (* offset 60 1000)))
    (reader-error nil (str "Unrecognized date/time syntax: " ts)))))

#_( TODO (defn ^:private read-date
  [s]
  (if (string? s)
    (parse-timestamp s)
    (reader-error nil "Instance literal expects a string for its timestamp."))))


(defn ^:private read-queue
  [elems]
  (if (vector? elems)
    (into cljscm.core.PersistentQueue/EMPTY elems)
    (reader-error nil "Queue literal expects a vector for its elements.")))


(defn ^:private read-uuid
  [uuid]
  (if (string? uuid)
    (UUID. uuid)
    (reader-error nil "UUID literal expects a string as its representation.")))

(def *tag-table* (atom {;"inst"  read-date TODO
                        "uuid"  read-uuid
                        "queue" read-queue}))

(def *default-data-reader-fn*
  (atom nil))

(defn maybe-read-tagged-type
  [rdr initch]
  (let [tag (read-symbol rdr initch)
        pfn (get @*tag-table* (str tag))
        dfn @*default-data-reader-fn*]
    (cond
     pfn (pfn (read rdr true nil false))
     dfn (dfn tag (read rdr true nil false))
     :else (reader-error rdr
                         "Could not find tag parser for " (str tag)
                         " in " (pr-str (keys @*tag-table*))))))

(defn register-tag-parser!
  [tag f]
  (let [tag (str tag)
        old-parser (get @*tag-table* tag)]
    (swap! *tag-table* assoc tag f)
    old-parser))

(defn deregister-tag-parser!
  [tag]
  (let [tag (str tag)
        old-parser (get @*tag-table* tag)]
    (swap! *tag-table* dissoc tag)
    old-parser))

(defn register-default-tag-parser!
  [f]
  (let [old-parser @*default-data-reader-fn*]
    (swap! *default-data-reader-fn* (fn [_] f))
    old-parser))

(defn deregister-default-tag-parser!
  []
  (let [old-parser @*default-data-reader-fn*]
    (swap! *default-data-reader-fn* (fn [_] nil))
    old-parser))
