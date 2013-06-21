;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

;(set! *warn-on-reflection* true)

(ns cljscm.analyzer
  (:refer-clojure :exclude [macroexpand-1])
  (:require [cljscm.conditional :as condc]))

(condc/platform-case
 :jvm (require '[clojure.java.io :as io])
 :gambit (require '[cljscm.reader :as reader]))

(declare resolve-var)
(declare resolve-existing-var)
(declare warning)
(def ^:dynamic *cljs-warn-on-undeclared* false)
(declare confirm-bindings)
(declare ^:dynamic *cljs-file*)

;; namespaces in gambit are just the symbol names.
(condc/platform-case
 :gambit (defn create-ns [x] x))

;; to resolve keywords like ::foo - the namespace
;; must be determined during analysis - the reader
;; did not know
(def ^:dynamic *reader-ns-name* (gensym "reader"))
(def ^:dynamic *reader-ns* (condc/platform-case :jvm (create-ns *reader-ns-name*)))

;in AOT, namespaces lives in analyzer. For runtime repl it needs to be core.
(condc/platform-case
 :jvm (def namespaces (atom '{cljscm.core {:name cljscm.core}
                              cljscm.user {:name cljscm.user}}))) ;defonce TODO

(defn get-namespaces []
  (condc/platform-case
   :jvm namespaces
   :gambit cljscm.core/namespaces))

;TODO not sure what I need from the actual Namespace class
(condc/platform-case :gambit (defn find-ns [sym] sym))

(defn reset-namespaces! []
  (reset! (get-namespaces)
    '{cljscm.core {:name cljscm.core}
      cljscm.user {:name cljscm.user}}))

(defn get-namespace [key]
  (@(get-namespaces) key))

(defn set-namespace [key val]
  (swap! (get-namespaces) assoc key val))

(def ^:dynamic *cljs-ns* 'cljscm.user)
(def ^:dynamic *cljs-file* nil)
(def ^:dynamic *cljs-warn-on-redef* true)
(def ^:dynamic *cljs-warn-on-dynamic* true)
(def ^:dynamic *cljs-warn-on-fn-var* true)
(def ^:dynamic *cljs-warn-fn-arity* true)
(def ^:dynamic *cljs-warn-fn-deprecated* true)
(def ^:dynamic *cljs-warn-protocol-deprecated* true)
(def ^:dynamic *unchecked-if* (atom false))
(def ^:dynamic *cljs-static-fns* false)
(def ^:dynamic *cljs-macros-path* "/cljscm/core_macros")
(def ^:dynamic *cljs-macros-is-classpath* true)
(def  -cljs-macros-loaded (atom false))

(defmacro no-warn [& body]
  `(binding [*cljs-warn-on-undeclared* false
             *cljs-warn-on-redef* false
             *cljs-warn-on-dynamic* false
             *cljs-warn-on-fn-var* false
             *cljs-warn-fn-arity* false
             *cljs-warn-fn-deprecated* false]
     ~@body))

(defn load-core []
  (condc/platform-case
   :jvm (when (not @-cljs-macros-loaded)
          (reset! -cljs-macros-loaded true)
          (binding [condc/*target-platform* :jvm] ;These forms will be evaled in :jvm
            (if *cljs-macros-is-classpath*
              (load *cljs-macros-path*)
              (load-file *cljs-macros-path*))))
   :gambit :TODO))

(condc/platform-case
 :jvm (defmacro with-core-macros
        [path & body]
        `(do
           (when (not= *cljs-macros-path* ~path)
             (reset! -cljs-macros-loaded false))
           (binding [*cljs-macros-path* ~path]
             ~@body))))

(condc/platform-case
 :jvm (defmacro with-core-macros-file
        [path & body]
        `(do
           (when (not= *cljs-macros-path* ~path)
             (reset! -cljs-macros-loaded false))
           (binding [*cljs-macros-path* ~path
                     *cljs-macros-is-classpath* false]
             ~@body))))

(defn empty-env [& locals]
  {:ns (@(get-namespaces) *cljs-ns*) :context :statement
   :locals (into {} (map #(vector % {:name % :local true})
                         locals))})

#_(defmacro-scm ^:private debug-prn
  [& args]
  `(.println System/err (str ~@args)))

(defn source-info [env]
  (when-let [line (:line env)]
    {:file *cljs-file*
     :line line}))

(defn message [env s]
  (str s (when (:line env)
           (str " at line " (:line env) " " *cljs-file*))))

(defn warning [env s]
  (condc/platform-case
   :jvm (binding [*out* *err*]
          (println (message env s)))
   :gambit (println (message env s)))) ; TODO *err* in :gambit.

(defn error
  ([env s] (error env s nil))
  ([env s cause]
   (ex-info (message env s)
            (assoc (source-info env) :tag :cljs/analysis-error)
            cause)))

(defn analysis-error? [ex]
  (= :cljs/analysis-error (:tag (ex-data ex))))

(defmacro wrapping-errors [env & body]
  (let [err (gensym "err")]
    `(try
       ~@body
       ~(condc/platform-case
         :jvm (case condc/*target-platform* ;macro definition vs macro expansion stages will differ on cross-compilation.
                :jvm `(catch Throwable ~err
                        (if (analysis-error? ~err)
                          (throw ~err)
                          ~(if (= condc/*current-platform* :jvm)
                             `(throw (error ~env (.getMessage ~err) ~err))
                             `(throw (error ~env (str ~err) ~err)))))
                :gambit `(catch cljscm.core/ExceptionInfo ~err
                           (if (analysis-error? ~err)
                             (throw ~err)
                             ~`(throw (error ~env (str ~err) ~err)))))
         :gambit `(catch cljscm.core/ExceptionInfo ~err
                    (if (analysis-error? ~err)
                      (throw ~err)
                      ~`(throw (error ~env (str ~err) ~err))))))))

(defn confirm-var-exists [env prefix suffix]
  (when *cljs-warn-on-undeclared*
    (let [crnt-ns (-> env :ns :name)]
      (when (= prefix crnt-ns)
        (when-not (-> @(get-namespaces) crnt-ns :defs suffix)
          (warning env
            (str "WARNING: Use of undeclared Var " prefix "/" suffix)))))))

(defn resolve-ns-alias [env name]
  (let [sym (symbol name)]
    (get (:requires (:ns env)) sym sym)))

(condc/platform-case
 :gambit (defn symbol-defined? [s]
        ((scm* {} with-exception-catcher)
         (fn [exc] (if ((scm* {} unbound-global-exception?) exc)
                     false
                     ((scm* {} raise) exc)))
         (fn [] ((scm* {} eval) s) true))))

(defn core-name?
  "Is sym visible from core in the current compilation namespace?"
  [env sym]
  (condc/platform-case
   :jvm (and (get (:defs (@(get-namespaces) 'cljscm.core)) sym)
             (not (contains? (set (-> env :ns :excludes)) sym)))
   :gambit (and (symbol-defined? (symbol "cljscm.core" (str sym)))
                (not (contains? (set (-> env :ns :excludes)) sym)))))

(defn resolve-var
  "Resolve a var. Accepts a side-effecting confirm fn for producing
   warnings about unresolved vars."
  ([env sym] (resolve-var env sym nil))
  ([env sym confirm]
     (if (= (namespace sym) "js")
       (do (when (some #{\.} (-> sym name str))
             (warning env (str "Invalid js form " sym)))
           {:name sym :ns 'js})
       (let [s (str sym)
             lb (-> env :locals sym)]
         (cond
           lb lb

           (namespace sym)
           (let [ns (namespace sym)
                 ns (if (= "clojure.core" ns) "cljscm.core" ns)
                 full-ns (resolve-ns-alias env ns)]
             (when confirm
               (confirm env full-ns (symbol (name sym))))
             (merge (get-in @(get-namespaces) [full-ns :defs (symbol (name sym))])
                    {:name (symbol (str full-ns) (str (name sym)))
                     :ns full-ns}))

           (some #{\.} (seq s))
           (let [[prefix _ suffix] (partition-by   #{\.} s)
                 [prefix suffix] [(symbol (apply str prefix)) (apply str suffix)]
                 lb (-> env :locals prefix)]
             (if lb
               {:name (symbol (str (:name lb) suffix))}
               (do
                 (when confirm
                   (confirm env prefix (symbol suffix)))
                 (merge (get-in @(get-namespaces) [prefix :defs (symbol suffix)])
                        {:name (if (= "" prefix) (symbol suffix) (symbol (str prefix) suffix))
                         :ns prefix}))))

           (get-in @(get-namespaces) [(-> env :ns :name) :uses sym])
           (let [full-ns (get-in @(get-namespaces) [(-> env :ns :name) :uses sym])]
             (merge
              (get-in @(get-namespaces) [full-ns :defs sym])
              {:name (symbol (str full-ns) (str sym))
               :ns (-> env :ns :name)}))

           (get-in @(get-namespaces) [(-> env :ns :name) :imports sym])
           (recur env (get-in @(get-namespaces) [(-> env :ns :name) :imports sym]) confirm)

           :else
           (let [full-ns (if (core-name? env sym)
                           'cljscm.core
                           (-> env :ns :name))]
             (when confirm
               (confirm env full-ns sym))
             (merge (get-in @(get-namespaces) [full-ns :defs sym])
                    {:name (symbol (str full-ns) (str sym))
                     :ns full-ns})))))))

(defn resolve-existing-var [env sym]
  (resolve-var env sym confirm-var-exists))

(defn confirm-bindings [env names]
  (doseq [name names]
    (let [env (merge env {:ns (@(get-namespaces) *cljs-ns*)})
          ev (resolve-existing-var env name)]
      (when (and *cljs-warn-on-dynamic*
                 ev (not (-> ev :dynamic)))
        (warning env
          (str "WARNING: " (:name ev) " not declared ^:dynamic"))))))

(declare analyze analyze-symbol analyze-seq)

(def specials '#{if case def fn* do let* loop* letfn* throw try* recur new set! ns deftype* defrecord* . extend scm-str* scm* & quote in-ns require})

(def ^:dynamic *recur-frames* nil)
(def ^:dynamic *loop-lets* nil)

(defmacro disallowing-recur [& body]
  `(binding [*recur-frames* (cons nil *recur-frames*)] ~@body))

(defn analyze-keyword
    [env sym]
    {:op :constant :env env
     :form (if (= (namespace sym) (name *reader-ns-name*))
               (keyword (-> env :ns :name name) (name sym))
               sym)})

(def prim-types #{'cljscm.core/Number 'cljscm.core/Pair 'cljscm.core/Boolean 'cljscm.core/Nil 'cljscm.core/Null
                  'cljscm.core/Char 'cljscm.core/Array 'cljscm.core/Symbol 'cljscm.core/Keyword
                  'cljscm.core/Procedure 'cljscm.core/String})

(defmulti parse (fn [op & rest] op))

(defmethod parse 'if
  [op env [_ test then else :as form] name]
  (assert (>= (count form) 3) "Too few arguments to if")
  (let [test-expr (disallowing-recur (analyze (assoc env :context :expr) test))
        then-expr (analyze env then)
        else-expr (analyze env else)]
    {:env env :op :if :form form
     :test test-expr :then then-expr :else else-expr
     :unchecked @*unchecked-if*
     :children [test-expr then-expr else-expr]}))

(defmethod parse 'case
  [op env [_ test & clauses :as form] _]
  (let [test-expr (disallowing-recur (analyze (assoc env :context :expr) test))
        [paired-clauses else] (if (odd? (count clauses))
                                [(butlast clauses) (last clauses)]
                                [clauses ::no-else])
        clause-exprs (seq (map (fn [[test-constant result-expr]]                                 
                                 [(let [analyzed-t (analyze (assoc env :context :expr) test-constant)]
                                    (if (or (#{:vector :invoke} (:op analyzed-t)))
                                      (map #(analyze (assoc env :context :expr) %) test-constant)
                                      [analyzed-t]))
                                  , (analyze (assoc env :context :expr) result-expr)])
                               (partition 2 paired-clauses)))
        else-expr (when (not= ::no-else else) (analyze (assoc env :context :expr) else))]
    {:env env :op :case :form form
     :test test-expr :clauses clause-exprs :else else-expr
     :children (vec (concat [test-expr] clause-exprs))}))

(defmethod parse 'throw
  [op env [_ throw :as form] name]
  (let [throw-expr (disallowing-recur (analyze (assoc env :context :expr) throw))]
    {:env env :op :throw :form form
     :throw throw-expr
     :children [throw-expr]}))

(defmethod parse 'try*
  [op env [_ & body :as form] name]
  (let [body (vec body)
        catchenv (update-in env [:context] #(if (= :expr %) :return %))
        tail (peek body)
        fblock (when (and (seq? tail) (= 'finally (first tail)))
                  (rest tail))
        finally (when fblock
                  (analyze (assoc env :context :statement) `(do ~@fblock)))
        body (if finally (pop body) body)
        tail (peek body)
        cblock (when (and (seq? tail)
                          (= 'catch (first tail)))
                 (rest tail))
        name (first cblock)
        locals (:locals catchenv)
        locals (if name
                 (assoc locals name {:name name})
                 locals)
        catch (when cblock
                (analyze (assoc catchenv :locals locals) `(do ~@(rest cblock))))
        body (if name (pop body) body)
        try (analyze (if (or name finally) catchenv env) `(do ~@body))]
    (when name (assert (not (namespace name)) "Can't qualify symbol in catch"))
    {:env env :op :try* :form form
     :try try
     :finally finally
     :name name
     :catch catch
     :children [try catch finally]}))

(defmethod parse 'def
  [op env form name]
  (assert (<= (count form) 4) "Too many forms supplied to def")
  (let [pfn (fn
              ([_ sym] {:sym sym})
              ([_ sym init] {:sym sym :init init})
              ([_ sym doc init] {:sym sym :doc doc :init init}))
        args (apply pfn form)
        sym (:sym args)
        sym-meta (meta sym)
        tag (-> sym meta :tag)
        protocol (-> sym meta :protocol)
        macro (-> sym meta :macro)
        dynamic (-> sym meta :dynamic)
        ns-name (-> env :ns :name)]
    (assert (not (namespace sym)) "Can't def ns-qualified name")
    (let [env (if (or (and (not= ns-name 'cljscm.core)
                           (core-name? env sym))
                      (get-in @(get-namespaces) [ns-name :uses sym]))
                (let [ev (resolve-existing-var (dissoc env :locals) sym)]
                  (when *cljs-warn-on-redef*
                    (warning env
                      (str "WARNING: " sym " already refers to: " (symbol (str (:ns ev)) (str sym))
                           " being replaced by: " (symbol (str ns-name) (str sym)))))
                  (swap! (get-namespaces) update-in [ns-name :excludes] (fn [c o] (conj (or c #{}) o)) sym)
                  (update-in env [:ns :excludes] (fn [c o] (conj (or c #{}) o)) sym))
                env)
          name (:name (resolve-var (dissoc env :locals) sym))
          init-expr (when (contains? args :init)
                      (disallowing-recur
                        (-> (analyze (assoc env :context :expr) (:init args) sym)
                            (assoc :toplevel true))))
          fn-var? (and init-expr (= (:op init-expr) :fn))
          export-as (when-let [export-val (-> sym meta :export)]
                      (if (= true export-val) name export-val))
          doc (or (:doc args) (-> sym meta :doc))]
      (when-let [v (get-in @(get-namespaces) [ns-name :defs sym])]
        (when (and *cljs-warn-on-fn-var*
                   (not (-> sym meta :declared))
                   (and (:fn-var v) (not fn-var?)))
          (warning env
            (str "WARNING: " (symbol (str ns-name) (str sym))
                 " no longer fn, references are stale"))))
      (swap! (get-namespaces) assoc-in [ns-name :defs sym]
                 (merge 
                   {:name name}
                   sym-meta
                   (when doc {:doc doc})
                   (when dynamic {:dynamic true})
                   (source-info env)
                   ;; the protocol a protocol fn belongs to
                   (when protocol
                     {:protocol protocol})
                   ;; symbol for reified protocol
                   (when-let [protocol-symbol (-> sym meta :protocol-symbol)]
                     {:protocol-symbol protocol-symbol})
                   (when macro
                     {:macro macro})
                   (when fn-var?
                     {:fn-var true
                      ;; protocol implementation context
                      :protocol-impl (:protocol-impl init-expr)
                      ;; inline protocol implementation context
                      :protocol-inline (:protocol-inline init-expr)
                      :variadic (:variadic init-expr)
                      :max-fixed-arity (:max-fixed-arity init-expr)
                      :method-params (map :params (:methods init-expr))})))
      (merge {:env env :op :def :form form
              :name name :doc doc :init init-expr}
             (when tag {:tag tag})
             (when dynamic {:dynamic true})
             (when export-as {:export export-as})
             (when init-expr {:children [init-expr]})))))

(comment (defn- analyze-fn-method [env locals meth]
  (letfn [(uniqify [[p & r]]
            (when p
              (cons (if (some #{p} r) (gensym (str p)) p)
                    (uniqify r))))]
   (let [params (first meth)
         fields (-> params meta ::fields)
         variadic (boolean (some '#{&} params))
         params (uniqify (remove '#{&} params))
         fixed-arity (count (if variadic (butlast params) params))
         body (next meth)
         gthis (and fields (gensym "this__"))
         locals (reduce (fn [m fld]
                          (assoc m fld
                                 {:name (symbol (str gthis "." (munge fld)))
                                  :field true
                                  :mutable (-> fld meta :mutable)}))
                        locals fields)
         locals (reduce (fn [m name] (assoc m name {:name (munge name)})) locals params)
         recur-frame {:names (vec (map munge params)) :flag (atom nil) :variadic variadic}
         block (binding [*recur-frames* (cons recur-frame *recur-frames*)]
                 #_(println "recur-frames" *recur-frames*)
                 (analyze-block (assoc env :context :return :locals locals) body))]

     (merge {:env env :variadic variadic :params (map munge params) :max-fixed-arity fixed-arity :gthis gthis :recurs @(:flag recur-frame)} block)))))

(defn- analyze-fn-method [env locals form type protocol-inline-head]
  (let [param-names (first form)
        variadic (boolean (some '#{&} param-names))
        param-names (vec (remove '#{&} param-names))
        body (next form)
        [locals params] (reduce (fn [[locals params] name]
                                  (let [param {:name name
                                               :tag (-> name meta :tag)
                                               :shadow (locals name)}]
                                    [(assoc locals name param) (conj params param)]))
                                [locals []] param-names)
        fixed-arity (count (if variadic (butlast params) params))
        recur-frame {:params params :flag (atom nil)}
        env (if protocol-inline-head (assoc env :this-name (first (first form))) env)
        expr (binding [*recur-frames* (cons recur-frame *recur-frames*)]
               (analyze (assoc env :context :return :locals locals) `(do ~@body)))]
    {:env env :variadic variadic :params params :max-fixed-arity fixed-arity
     :type type :form form :recurs @(:flag recur-frame) :expr expr}))

(comment (defmethod parse 'fn*
  [op env [_ & args] name]
  (let [[name meths] (if (symbol? (first args))
                       [(first args) (next args)]
                       [name (seq args)])
        ;;turn (fn [] ...) into (fn ([]...))
        meths (if (vector? (first meths)) (list meths) meths)
        mname (when name (str (munge name)  "---recur"))
        locals (:locals env)
        locals (if name (assoc locals name {:name mname}) locals)
        env (assoc env :recur-name (or mname (gensym "recurfn")))
        menv (if (> (count meths) 1) (assoc env :context :expr) env)
        methods (map #(analyze-fn-method menv locals %) meths)
        max-fixed-arity (apply max (map :max-fixed-arity methods))
        variadic (boolean (some :variadic methods))]
    ;;todo - validate unique arities, at most one variadic, variadic takes max required args
    {:env env :op :fn :name mname :methods methods :variadic variadic :recur-frames *recur-frames*
     :jsdoc []
     :max-fixed-arity max-fixed-arity})))

(defmethod parse 'fn*
  [op env [_ & args :as form] name]
  (let [named-by-def name
        [name meths] (if (symbol? (first args))
                       [(first args) (next args)]
                       [name (seq args)])
        ;;turn (fn [] ...) into (fn ([]...))
        meths (if (vector? (first meths)) (list meths) meths)
        recur-name (symbol (or (and named-by-def (:name (resolve-var env name)))
                               name
                               "cljscm.compiler/recurfn"))
        locals (:locals env)
;        name recur-name
        env (assoc env :recur-name recur-name)
        type (-> form meta ::type)
        fields (-> form meta ::fields)
        protocol-impl (-> form meta :protocol-impl)
        protocol-inline (-> form meta :protocol-inline)
        single-arity (-> form meta :single-arity)
        locals (if (and name (not named-by-def))
                 (assoc locals name {:name recur-name :shadow (locals recur-name)})
                 locals)
        locals (reduce (fn [m fld]
                         (assoc m fld
                                {:name fld
                                 :field true
                                 :mutable (-> fld meta :mutable)
                                 :tag (-> fld meta :tag)
                                 :shadow (m fld)}))
                       locals fields)

        menv (if (> (count meths) 1) (assoc env :context :expr) env)
        menv (merge menv
                    {:protocol-impl protocol-impl
                     :protocol-inline protocol-inline})
        methods (map #(analyze-fn-method menv locals % type protocol-inline) meths)
        max-fixed-arity (apply max (map :max-fixed-arity methods))
        variadic (boolean (some :variadic methods))
        locals (if (and name (not named-by-def))
                 (update-in locals [name] assoc
                            :fn-var true
                            :name recur-name
                            :variadic variadic
                            :max-fixed-arity max-fixed-arity
                            :method-params (map :params methods))
                 locals)
        methods (if name
                  ;; a second pass with knowledge of our function-ness/arity
                  ;; lets us optimize self calls
                  (no-warn (doall (map #(analyze-fn-method menv locals % type protocol-inline) meths)))
                  methods)]
    ;;todo - validate unique arities, at most one variadic, variadic takes max required args
    {:env env :op :fn :form form :name name :methods methods :variadic variadic
     :recur-frames *recur-frames* :loop-lets *loop-lets*
     :jsdoc [(when variadic "@param {...*} var_args")]
     :max-fixed-arity max-fixed-arity
     :protocol-impl protocol-impl
     :protocol-inline protocol-inline
     :single-arity single-arity
     :children (mapv :expr methods)}))

(defmethod parse 'letfn*
  [op env [_ bindings & exprs :as form] name]
  (assert (and (vector? bindings) (even? (count bindings))) "bindings must be vector of even number of elements")
  (let [n->fexpr (into {} (map (juxt first second) (partition 2 bindings)))
        names    (keys n->fexpr)
        context  (:context env)
        [meth-env bes]
        (reduce (fn [[{:keys [locals] :as env} bes] n]
                  (let [be {:name   n
                            :tag    (-> n meta :tag)
                            :local  true
                            :shadow (locals n)}]
                    [(assoc-in env [:locals n] be)
                     (conj bes be)]))
                [env []] names)
        meth-env (assoc meth-env :context :expr)
        bes (vec (map (fn [{:keys [name shadow] :as be}]
                        (let [env (assoc-in meth-env [:locals name] shadow)]
                          (assoc be :init (analyze env (n->fexpr name)))))
                      bes))
        expr (analyze (assoc meth-env :context (if (= :expr context) :return context)) `(do ~@exprs))]
    {:env env :op :letfn :bindings bes :expr expr :form form
     :children (conj (vec (map :init bes)) expr)}))

(defmethod parse 'do
  [op env [_ & exprs :as form] _]
  (let [statements (disallowing-recur
                     (seq (map #(analyze (assoc env :context :statement) %) (butlast exprs))))
        ret (if (<= (count exprs) 1)
              (analyze env (first exprs))
              (analyze (assoc env :context (if (= :statement (:context env)) :statement :return)) (last exprs)))]
    {:env env :op :do :form form
     :statements statements :ret ret
     :children (conj (vec statements) ret)}))

(comment (defn analyze-let
  [encl-env [_ bindings & exprs :as form] is-loop]
  (assert (and (vector? bindings) (even? (count bindings))) "bindings must be vector of even number of elements")
  (let [context (:context encl-env)
        [bes env]
        (disallowing-recur
          (loop [bes []
                 env (assoc encl-env :context :expr)
                 bindings (seq (partition 2 bindings))]
            (if-let [[name init] (first bindings)]
              (do
                (assert (not (or (namespace name) (.contains (str name) "."))) (str "Invalid local name: " name))
                (let [init-expr (analyze env init)
                      be {:name (gensym (str (munge name) "__")) :init init-expr}]
                  (recur (conj bes be)
                         (assoc-in env [:locals name] be)
                         (next bindings))))
              [bes env])))
        recur-frame (when is-loop {:names (vec (map :name bes)) :flag (atom nil)})
        recur-name (when is-loop (gensym "recurlet"))
        {:keys [statements ret children]}
        (binding [*recur-frames* (if recur-frame (cons recur-frame *recur-frames*) *recur-frames*)]
          #_(println "recur-frames2" recur-frame *recur-frames*)
          (analyze-block (into (assoc env :context (if (= :expr context) :return context))
                               (when recur-name [[:recur-name recur-name]])) exprs))]
    (into
     {:env encl-env :op :let :loop is-loop
      :bindings bes :statements statements :ret ret :form form :children (into [children] (map :init bes))}
     (when recur-name [[:recur-name recur-name]])))))

(defn analyze-let
  [encl-env [_ bindings & exprs :as form] is-loop]
  (assert (and (vector? bindings) (even? (count bindings))) "bindings must be vector of even number of elements")
  (let [context (:context encl-env)
        recur-name (when is-loop (symbol "cljscm.compiler" "recurlet"))
        [bes env]
        (disallowing-recur
          (loop [bes []
                 env (assoc encl-env :context :expr)
                 bindings (seq (partition 2 bindings))]
            (if-let [[name init] (first bindings)]
              (do
                (assert (not (or (namespace name) (some #{\.} (seq (str name))))) (str "Invalid local name: " name))
                (let [init-expr (binding [*loop-lets* (cons {:params bes} (or *loop-lets* ()))]
                                  (analyze env init))
                      be {:name name
                          :init init-expr
                          :tag (or (-> name meta :tag)
                                   (-> init-expr :tag)
                                   (-> init-expr :info :tag))
                          :local true
                          :shadow (-> env :locals name)}
                      be (if (= (:op init-expr) :fn)
                           (merge be
                                  {:fn-var true
                                   :variadic (:variadic init-expr)
                                   :max-fixed-arity (:max-fixed-arity init-expr)
                                   :method-params (map :params (:methods init-expr))})
                           be)]
                  (recur (conj bes be)
                         (assoc-in env [:locals name] be)
                         (next bindings))))
              [bes env])))
        env (if recur-name (assoc env :recur-name recur-name) env)
        recur-frame (when is-loop {:params bes :flag (atom nil)})
        expr
        (binding [*recur-frames* (if recur-frame (cons recur-frame *recur-frames*) *recur-frames*)
                  *loop-lets* (cond
                                is-loop (or *loop-lets* ())
                                *loop-lets* (cons {:params bes} *loop-lets*))]
          (analyze (assoc env :context (if (= :expr context) :return context)) `(do ~@exprs)))]
    (into
     {:env encl-env :op (if is-loop :loop :let)
      :bindings bes :expr expr :form form
      :children (conj (vec (map :init bes)) expr)}
     (when recur-name [[:recur-name recur-name]]))))

(defmethod parse 'let*
  [op encl-env form _]
  (analyze-let encl-env form false))

(defmethod parse 'loop*
  [op encl-env form _]
  (analyze-let encl-env form true))

(comment (defmethod parse 'recur
  [op env [_ & exprs] _]
  (let [context (:context env)
        frame (first *recur-frames*)]
    (assert frame (str  "Can't recur here: " (:line env)))
    (assert (or (= (count exprs) (count (:names frame)))
                (and (>= (count exprs) (dec (count (:names frame))))
                     (:variadic frame))) (str "recur argument count mismatch: " (:line env) " " frame))
    (reset! (:flag frame) true)
    (assoc {:env env :op :recur}
      :frame frame
      :exprs (disallowing-recur (vec (map #(analyze (assoc env :context :expr) %) exprs)))))))

(defmethod parse 'recur
  [op env [_ & exprs :as form] _]
  (let [context (:context env)
        frame (first *recur-frames*)
        exprs (disallowing-recur (vec (map #(analyze (assoc env :context :expr) %) exprs)))]
    (assert frame "Can't recur here")
    (assert (= (count exprs) (count (:params frame))) "recur argument count mismatch")
    (reset! (:flag frame) true)
    (assoc {:env env :op :recur :form form}
      :frame frame
      :exprs exprs
      :children exprs)))

(defmethod parse 'quote
  [_ env [_ x] _]
  {:op :constant :env env :form x})

(defmethod parse 'new
  [_ env [_ ctor & args :as form] _]
  (assert (symbol? ctor) "First arg to new must be a symbol")
  (disallowing-recur
   (let [enve (assoc env :context :expr)
         ctorexpr (analyze enve ctor)
         argexprs (vec (map #(analyze enve %) args))
         known-num-fields (:num-fields (resolve-existing-var env ctor))
         argc (count args)]
     (when (and known-num-fields (not= known-num-fields argc))
       (warning env
         (str "WARNING: Wrong number of args (" argc ") passed to " ctor)))

     {:env env :op :new :form form :ctor ctorexpr :args argexprs
      :children (into [ctorexpr] argexprs)})))

(defmethod parse 'set!
  [_ env [_ target val alt :as form] _]
  (let [[target val] (if alt
                       ;; (set! o -prop val)
                       [`(. ~target ~val) alt]
                       [target val])]
    (disallowing-recur
     (let [enve (assoc env :context :expr)
           targetexpr (cond
                       ;; TODO: proper resolve
                       (= target '*unchecked-if*)
                       (do
                         (reset! *unchecked-if* val)
                         ::set-unchecked-if)

                       (symbol? target)
                       (do
                         (let [local (-> env :locals target)]
                           (assert (or (nil? local)
                                       (and (:field local)
                                            (:mutable local)))
                                   "Can't set! local var or non-mutable field"))
                         (analyze-symbol enve target))

                       :else
                       (when (seq? target)
                         (let [targetexpr (analyze-seq enve target nil)]
                           (when (:field targetexpr)
                             targetexpr))))
           valexpr (analyze enve val)]
       (assert targetexpr "set! target must be a field or a symbol naming a var")
       (cond
        (= targetexpr ::set-unchecked-if) {:env env :op :no-op}
        :else {:env env :op :set! :form form :target targetexpr :val valexpr
               :children [targetexpr valexpr]})))))

(defn munge-path [ss]
  (clojure.lang.Compiler/munge (str ss)))

(defn ns->relpath-cljscm [s]
  (str (apply str (map #(get {\. \/} % %) (seq (munge-path s)))) ".cljscm"))

(defn ns->relpath-clj [s]
  (str (apply str (map #(get {\. \/} % %) (seq (munge-path s)))) ".clj"))

(declare analyze-file)

(defn analyze-deps [deps]
  (doseq [dep deps]
    (when-not (contains? @(get-namespaces) dep)
      (let [relpath (ns->relpath-cljscm dep)]
        (if (condc/platform-case :jvm (io/resource relpath) :gambit true)
          (analyze-file relpath)
          (analyze-file (ns->relpath-clj dep)))))))

(defn error-msg [spec msg] (str msg "; offending spec: " (pr-str spec)))

(defn get-deps [ns-name]
  (or (get-in @(get-namespaces) [ns-name :deps])
      (let [a (atom #{})]
        (swap! (get-namespaces) #(assoc-in % [ns-name :deps] a))
        a)))
(defn get-aliases [ns-name]
  (or (get-in @(get-namespaces) [ns-name :aliases])
      (let [a (atom {:fns #{} :macros #{}})]
        (swap! (get-namespaces) #(assoc-in % [ns-name :aliases] a))
        a)))

(defn parse-require-spec [macros? ns-name spec]
  (assert (or (symbol? spec) (vector? spec))
          (error-msg spec "Only [lib.ns & options] and lib.ns specs supported in :require / :require-macros"))
  (when (vector? spec)
    (assert (symbol? (first spec))
            (error-msg spec "Library name must be specified as a symbol in :require / :require-macros"))
    (assert (odd? (count spec))
            (error-msg spec "Only :as alias and :refer (names) options supported in :require"))
    (assert (every? #{:as :refer} (map first (partition 2 (next spec))))
            (error-msg spec "Only :as and :refer options supported in :require / :require-macros"))
    (assert (let [fs (frequencies (next spec))]
              (and (<= (fs :as 0) 1)
                   (<= (fs :refer 0) 1)))
            (error-msg spec "Each of :as and :refer options may only be specified once in :require / :require-macros")))
  (if (symbol? spec)
    (recur macros? ns-name [spec])
    (let [deps (get-deps ns-name)
          aliases (get-aliases ns-name)
          [lib & opts] spec
          {alias :as referred :refer :or {alias lib}} (apply hash-map opts)
          [rk uk] (if macros? [:require-macros :use-macros] [:require :use])]
      (when alias
        (let [alias-type (if macros? :macros :fns)]
          (when (not (contains? (alias-type @aliases)
                                alias))
            (println "CAUTION: " (error-msg spec ":as alias must be unique")))
          (swap! aliases
                 update-in [alias-type]
                 conj alias)))
      (assert (or (symbol? alias) (nil? alias))
              (error-msg spec ":as must be followed by a symbol in :require / :require-macros"))
      (assert (or (and (sequential? referred) (every? symbol? referred))
                  (nil? referred))
              (error-msg spec ":refer must be followed by a sequence of symbols in :require / :require-macros"))
      (when-not macros?
        (swap! deps conj lib))
      (merge (when alias {rk {alias lib}})
             (when referred {uk (apply hash-map (interleave referred (repeat lib)))})))))

(defmethod parse 'ns
  [_ env [_ name & args :as form] _]
  (assert (symbol? name) "Namespaces must be named by a symbol.")
  (let [docstring (if (string? (first args)) (first args) nil)
        args      (if docstring (next args) args)
        excludes
        (reduce (fn [s [k exclude xs]]
                  (if (= k :refer-clojure)
                    (do
                      (assert (= exclude :exclude) "Only [:refer-clojure :exclude (names)] form supported")
                      (assert (not (seq s)) "Only one :refer-clojure form is allowed per namespace definition")
                      (into s xs))
                    s))
                #{} args)
        deps (get-deps name)
        aliases (get-aliases name)
        valid-forms (atom #{:use :use-macros :require :require-macros :import})
        use->require (fn use->require [[lib kw referred :as spec]]
                       (assert (and (symbol? lib) (= :only kw) (sequential? referred) (every? symbol? referred))
                               (error-msg spec "Only [lib.ns :only (names)] specs supported in :use / :use-macros"))
                       [lib :refer referred])
        parse-import-spec (fn parse-import-spec [spec]
                            (assert (and (symbol? spec) (nil? (namespace spec)))
                                    (error-msg spec "Only lib.Ctor specs supported in :import"))
                            (swap! deps conj spec)
                            (let [ctor-sym (symbol (apply str (drop 1 (drop-while (complement #{\.}) (seq (str spec))))))]
                              {:import  {ctor-sym spec}
                               :require {ctor-sym spec}}))
        spec-parsers {:require        (partial parse-require-spec false name)
                      :require-macros (partial parse-require-spec true name)
                      :use            (comp (partial parse-require-spec false name) use->require)
                      :use-macros     (comp (partial parse-require-spec true name) use->require)
                      :import         parse-import-spec}
        {uses :use requires :require uses-macros :use-macros requires-macros :require-macros imports :import :as params}
        (reduce (fn [m [k & libs]]
                  (assert (#{:use :use-macros :require :require-macros :import} k)
                          "Only :refer-clojure, :require, :require-macros, :use and :use-macros libspecs supported")
                  (assert (@valid-forms k)
                          (str "Only one " k " form is allowed per namespace definition"))
                  (swap! valid-forms disj k)
                  (apply merge-with merge m (map (spec-parsers k) libs)))
                {} (remove (fn [[r]] (= r :refer-clojure)) args))]
    (when (seq @deps)
      (analyze-deps @deps))
    (set! *cljs-ns* name)
    (set! *reader-ns* (create-ns name))
    (set! *ns* *reader-ns*)
    (load-core)
    (doseq [nsym (concat (vals requires-macros) (vals uses-macros))]
      (clojure.core/require nsym))
    (swap! (get-namespaces) #(-> %
                           (assoc-in [name :name] name)
                           (assoc-in [name :doc] docstring)
                           (assoc-in [name :excludes] excludes)
                           (assoc-in [name :uses] uses)
                           (update-in [name :requires] merge requires)
                           (assoc-in [name :uses-macros] uses-macros)
                           (update-in [name :requires-macros] merge
                                     (into {} (map (fn [[alias nsym]]
                                                     [alias (find-ns nsym)])
                                                   requires-macros)))
                           (assoc-in [name :imports] imports)))
    {:env env :op :ns :form form :name name :doc docstring :uses uses :requires requires :imports imports
     :uses-macros uses-macros :requires-macros requires-macros :excludes excludes}))

(comment see no constructor (defmethod parse 'deftype*
  [_ env [_ tsym fields & opts] _]
  (let [t (munge (:name (resolve-var (dissoc env :locals) tsym)))
        no-constructor ((set opts) :no-constructor)]
    (swap! (get-namespaces) update-in [(-> env :ns :name) :defs tsym]
           (fn [m]
             (let [m (assoc (or m {}) :name t)]
               (if-let [line (:line env)]
                 (-> m
                     (assoc :file *cljs-file*)
                     (assoc :line line))
                 m))))
    (conj {:env env :op :deftype* :t t :fields fields}
          (when no-constructor [:no-constructor true])))))

(defmethod parse 'deftype*
  [_ env [_ tsym fields pmasks :as form] _]
  (let [t (:name (resolve-var (dissoc env :locals) tsym))]
    (swap! (get-namespaces) update-in [(-> env :ns :name) :defs tsym]
           (fn [m]
             (let [m (assoc (or m {})
                       :name t
                       :type true
                       :num-fields (count fields))]
               (merge m
                 {:protocols (-> tsym meta :protocols)}
                 (source-info env)))))
    {:env env :op :deftype* :form form :t t :fields fields :pmasks pmasks}))

(comment NOTE I commented out defrecord* previously.)

(defmethod parse 'defrecord*
  [_ env [_ tsym fields pmasks :as form] _]
  (let [t (:name (resolve-var (dissoc env :locals) tsym))]
    (swap! (get-namespaces) update-in [(-> env :ns :name) :defs tsym]
           (fn [m]
             (let [m (assoc (or m {}) :name t :type true)]
               (merge m
                 {:protocols (-> tsym meta :protocols)}
                 (source-info env)))))
    {:env env :op :defrecord* :form form :t t :fields fields :pmasks pmasks}))

(defmethod parse 'extend [op env [_ etype & impls] _]
  (let [prot-impl-pairs (partition 2 impls)
        e-type-rslvd (resolve-var env etype)
        analyzed-impls (map (fn [[prot-name meth-map]]
                              (let [prot-v (resolve-var env prot-name)] 
                                [prot-v
                                 (map (fn [[meth-key meth-impl]]
                                        [(analyze env (symbol (namespace (:name prot-v)) (name meth-key)))
                                         (analyze (assoc env :context :return) meth-impl (name meth-key))])
                                      meth-map)]))
                            prot-impl-pairs)]
    (swap! (get-namespaces) update-in [:proto-implementers] ;for fast-path dispatch compilation. proto-methname-symbol => set-of-types lookup.
           (fn [mp] (reduce (fn [m proname]
                              (update-in m [proname]
                                         (comp set conj) (:name e-type-rslvd)))
                            mp
                            (for [[proname meths] analyzed-impls] (:name proname))))) ;[methname _] meths
    {:env env :op :extend :etype e-type-rslvd :impls analyzed-impls :base-type? (prim-types (:name e-type-rslvd))}))

;(for [[proname meths] (:impls a) [methname _] meths] (:name (:info methname)))
;; dot accessor code

(defn ^boolean property-symbol? [sym]
  (and (symbol? sym)
       (= \- (first (name sym)))))

(defn- classify-dot-form
  [[target member args]]
  [(cond (nil? target) ::error
         :default      ::expr)
   (cond (property-symbol? member) ::property
         (symbol? member)          ::symbol
         (seq? member)             ::list
         :default                  ::error)
   (cond (nil? args) ()
         :default    ::expr)])

(defmulti build-dot-form #(classify-dot-form %))

;; (. o -p)
;; (. (...) -p)
(defmethod build-dot-form [::expr ::property ()]
  [[target prop _]]
  {:dot-action ::access :target target :field (-> prop name (subs 1) symbol)})

;; (. o -p <args>)
(defmethod build-dot-form [::expr ::property ::list]
  [[target prop args]]
  (throw (Error. (str "Cannot provide arguments " args " on property access " prop))))

(defn- build-method-call
  "Builds the intermediate method call map used to reason about the parsed form during
  compilation."
  [target meth args]
  (if (symbol? meth)
    {:dot-action ::call :target target :method meth :args args}
    {:dot-action ::call :target target :method (first meth) :args args}))

;; (. o m 1 2)
(defmethod build-dot-form [::expr ::symbol ::expr]
  [[target meth args]]
  (build-method-call target meth args))

;; (. o m)
(defmethod build-dot-form [::expr ::symbol ()]
  [[target meth args]]
  (build-method-call target meth args))

;; (. o (m))
;; (. o (m 1 2))
(defmethod build-dot-form [::expr ::list ()]
  [[target meth-expr _]]
  (build-method-call target (first meth-expr) (rest meth-expr)))

(defmethod build-dot-form :default
  [dot-form]
  (throw (Error. (str "Unknown dot form of " (list* '. dot-form) " with classification " (classify-dot-form dot-form)))))

(defmethod parse '.
  [_ env [_ target & [field & member+] :as form] _]
  (disallowing-recur
   (let [{:keys [dot-action target method field args]} (build-dot-form [target field member+])
         enve        (assoc env :context :expr)
         targetexpr  (analyze enve target)]
     (case dot-action
           ::access {:env env :op :dot :form form
                     :target targetexpr
                     :field field
                     :children [targetexpr]
                     :tag (-> form meta :tag)}
           ::call   (let [argexprs (map #(analyze enve %) args)]
                      {:env env :op :dot :form form
                       :target targetexpr
                       :method method
                       :args argexprs
                       :children (into [targetexpr] argexprs)
                       :tag (-> form meta :tag)})))))

(defmethod parse 'scm* [op env [s symbol-map & form] _]
  {:env env :op :scm :children [] :form form :symbol-map symbol-map :tag (-> s meta :tag)})

(defmethod parse 'scm-str*
  [op env [_ jsform & args :as form] _]
  (assert (string? jsform))
  (throw "Temporarily disabled")
  #_(if args
      (disallowing-recur
        (let [seg (fn seg [^String s]
                    (let [idx (.indexOf s "~{")]
                      (if (= -1 idx)
                        (list s)
                        (let [end (.indexOf s "}" idx)]
                          (cons (subs s 0 idx) (seg (subs s (inc end))))))))
              enve (assoc env :context :expr)
              argexprs (vec (map #(analyze enve %) args))]
          {:env env :op :scm-str :segs (seg jsform) :args argexprs
           :tag (-> form meta :tag) :form form :children argexprs}))
      (let [interp (fn interp [^String s]
                     (let [idx (.indexOf s "~{")]
                       (if (= -1 idx)
                         (list s)
                         (let [end (.indexOf s "}" idx)
                               inner (:name (resolve-existing-var env (symbol (subs s (+ 2 idx) end))))]
                           (cons (subs s 0 idx) (cons inner (interp (subs s (inc end)))))))))]
        {:env env :op :scm-str :form form :code (apply str (interp jsform))
         :tag (-> form meta :tag)})))

(defn parse-invoke
  [env [f & args :as form]]
  (disallowing-recur
   (let [enve (assoc env :context :expr)
         fexpr (analyze enve f)
         argexprs (vec (map #(analyze enve %) args))
         argc (count args)]
     (if (and *cljs-warn-fn-arity* (-> fexpr :info :fn-var))
       (let [{:keys [variadic max-fixed-arity method-params name]} (:info fexpr)]
         (when (and (not (some #{argc} (map count method-params)))
                    (or (not variadic)
                        (and variadic (< argc max-fixed-arity))))
           (warning env
             (str "WARNING: Wrong number of args (" argc ") passed to " name)))))
     (if (and *cljs-warn-fn-deprecated* (-> fexpr :info :deprecated)
              (not (-> form meta :deprecation-nowarn)))
       (warning env
         (str "WARNING: " (-> fexpr :info :name) " is deprecated.")))
     {:env env :op :invoke :form form :f fexpr :args argexprs
      :tag (or (-> fexpr :info :tag) (-> form meta :tag)) :children (into [fexpr] argexprs)})))

(defmethod parse 'in-ns [op env [_ [_ ns-name] :as form] _]
  (set! *cljs-ns* ns-name)
  (parse-invoke env form))

(defmethod parse 'require [op env [_ & specs :as form] _]
  (doall (map (fn [spec]
                (let [spec (if (and (coll? spec) (= 'quote (first spec))) (second spec) spec)
                      preq (merge (parse-require-spec false *cljs-ns* spec)
                                  (parse-require-spec true *cljs-ns* spec))]
                  (do
                    (println "; adding " preq)
                    (swap! (get-namespaces) #(update-in % [*cljs-ns* :requires] merge (:require preq) (:require-macros preq)))))) specs))
  (parse-invoke env form))

(defn analyze-symbol
  "Finds the var associated with sym"
  [env sym]
  (let [ret {:env env :form sym}
        lb (-> env :locals sym)]
    (if lb
      (assoc ret :op :var :info lb)
      (assoc ret :op :var :info (resolve-existing-var env sym)))))

(defn find-interned-var [ns sym]
  "returns var in jvm. returns the def info in gambit."
  (when ns
    (condc/platform-case
     :jvm (.findInternedVar ^clojure.lang.Namespace ns sym)
     :gambit (and (get-in @(get-namespaces) [ns :defs sym])
                  (symbol (str ns) (str sym))))))

(defn is-macro [mvar]
  (condc/platform-case
   :jvm (.isMacro ^clojure.lang.Var mvar)
   :gambit (get-in @(get-namespaces) [(symbol (namespace mvar))
                                      :defs
                                      (symbol (name mvar))
                                      :macro])))

(defn get-expander [sym env]
  (let [mvar
        (when-not (or (-> env :locals sym)        ;locals hide macros
                      (and false ;TODO: excludes excludes by symbol, regardless of import namespace, including erasing your current *ns* deffed symbols.
                           (or (-> env :ns :excludes sym)
                               (get-in @(get-namespaces) [(-> env :ns :name) :excludes sym]))
                           (not (or (-> env :ns :uses-macros sym)
                                    (get-in @(get-namespaces) [(-> env :ns :name) :uses-macros sym])))))
          (if-let [nstr (and (namespace sym)
                             (str (resolve-ns-alias env (symbol (namespace sym)))))]
            (when-let [ns (cond
                            (= "clojure.core" nstr) (find-ns 'cljscm.core)
                            (some #{\.} (seq nstr)) (find-ns (symbol nstr))
                            :else
                            (-> env :ns :requires-macros (get (symbol nstr))))]
              (find-interned-var ns (symbol (name sym))))
            (if-let [nsym (-> env :ns :uses-macros sym)]
              (find-interned-var (find-ns nsym) sym)
              (or
               (find-interned-var (find-ns *cljs-ns*) sym)
               (find-interned-var (find-ns 'cljscm.core) sym)))))]
    (when (and mvar (is-macro mvar))
      (condc/platform-case
       :jvm @mvar
       :gambit ((scm* {} eval) mvar)))))

(defn macroexpand-1 [env form]
  (let [op (first form)]
    (if (specials op)
      form
      (if-let [mac (and (symbol? op) (get-expander op env))]
        (condc/platform-case
         :jvm (binding [*ns* (create-ns *cljs-ns*)]
                (apply mac form env (rest form)))
         :gambit (apply mac form env (rest form)))
        (if (symbol? op)
          (let [opname (str op)]
            (cond
              (= (first opname) \.) (let [[target & args] (next form)]
                                      (with-meta (list* '. target (symbol (subs opname 1)) args)
                                        (meta form)))
              (= (last opname) \.) (with-meta
                                     (list* 'new (symbol (subs opname 0 (dec (count opname)))) (next form))
                                     (meta form))
              :else form))
          form)))))

(defn analyze-seq
  [env form name]
  (let [op (first form)]
    (assert (not (nil? op)) "Can't call nil")
    (let [mform (macroexpand-1 env form)]
      (if (identical? form mform)
        (wrapping-errors env
          (if (specials op)
            (parse op env form name)
            (parse-invoke env form)))
        (analyze env mform name)))))

(declare analyze-wrap-meta)

(defn analyze-map
  [env form name]
  (let [expr-env (assoc env :context :expr)
        simple-keys? (every? #(or (string? %) (keyword? %))
                             (keys form))
        ks (disallowing-recur (vec (map #(analyze expr-env % name) (keys form))))
        vs (disallowing-recur (vec (map #(analyze expr-env % name) (vals form))))]
    (analyze-wrap-meta {:op :map :env env :form form
                        :keys ks :vals vs :simple-keys? simple-keys?
                        :children (vec (interleave ks vs))}
                       name)))

(defn analyze-vector
  [env form name]
  (let [expr-env (assoc env :context :expr)
        items (disallowing-recur (vec (map #(analyze expr-env % name) form)))]
    (analyze-wrap-meta {:op :vector :env env :form form :items items :children items} name)))

(defn analyze-set
  [env form name]
  (let [expr-env (assoc env :context :expr)
        items (disallowing-recur (vec (map #(analyze expr-env % name) form)))]
    (analyze-wrap-meta {:op :set :env env :form form :items items :children items} name)))

(defn analyze-wrap-meta [expr name]
  (let [form (:form expr)]
    (if (meta form)
      (let [env (:env expr) ; take on expr's context ourselves
            expr (assoc-in expr [:env :context] :expr) ; change expr to :expr
            meta-expr (analyze-map (:env expr) (meta form) name)]
        {:op :meta :env env :form form
         :meta meta-expr :expr expr :children [meta-expr expr]})
      expr)))

(defn analyze
  "Given an environment, a map containing {:locals (mapping of names to bindings), :context
  (one of :statement, :expr, :return), :ns (a symbol naming the
  compilation ns)}, and form, returns an expression object (a map
  containing at least :form, :op and :env keys). If expr has any (immediately)
  nested exprs, must have :children [exprs...] entry. This will
  facilitate code walking without knowing the details of the op set."
  ([env form] (analyze env form nil))
  ([env form name]
   (wrapping-errors env
     (let [form (if (instance? (condc/platform-case
                                :jvm clojure.lang.LazySeq
                                :gambit cljscm.core/LazySeq) form)
                  (or (seq form) ())
                  form)
           env (assoc env :line
                      (or (-> form meta :line)
                       (:line env)))
           env (assoc env :column
                      (or (-> form meta :column)
                          (:column env)))]
       (load-core)
       (cond
        (symbol? form) (analyze-symbol env form)
        (and (seq? form) (seq form)) (analyze-seq env form name)
        (map? form) (analyze-map env form name)
        (vector? form) (analyze-vector env form name)
        (set? form) (analyze-set env form name)
        (keyword? form) (analyze-keyword env form)
        :else {:op :constant :env env :form form})))))

(def readtable) ;TODO

(condc/platform-case
 :jvm
 (defn analyze-file
   [^String f]
   (let [res (if (re-find #"^file://" f) (java.net.URL. f) (io/resource f))] ;res (or res (java.net.URL. (str "file:/Users/nathansorenson/src/c-clojure/src/cljs/" f)))
     (assert res (str "Can't find " f " in classpath"))
     (binding [*cljs-ns* 'cljscm.user
               *reader-ns* (create-ns *cljs-ns*)
               *cljs-file* (.getPath ^java.net.URL res)
               *ns* *reader-ns*
               condc/*target-platform* :gambit]
       (with-open [r (io/reader res)]
         (let [env (empty-env)
               pbr (clojure.lang.LineNumberingPushbackReader. r)
               eof (Object.)]
           (loop [r (read pbr false eof false)]
             (let [env (assoc env :ns (get-namespace *cljs-ns*))]
               (when-not (identical? eof r)
                 (analyze env r)
                 (recur (read pbr false eof false))))))))))
 :gambit
 (defn analyze-file
   [f]
   (let [find-file (fn [filename classpath]
                     (->> classpath
                          (map #(str % "/" filename))
                          (filter #((cljscm.core/scm* {} file-exists?) %))
                          (first)))
         f (find-file f ["."])]
     (assert f (str "Can't find " f " in classpath"))
     (binding [*cljs-ns* 'cljscm.user
               *cljs-file* f
               condc/*target-platform* :gambit]
       (let [forms (reader/file-seq-reader f)
             env (empty-env)]
         (doall (map (fn [f]
                       (let [env (assoc env :ns (get-namespace *cljs-ns*))]
                         (analyze env f)))
                     forms)))))))
