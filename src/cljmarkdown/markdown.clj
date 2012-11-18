(ns cljmarkdown.markdown)

(defn letter
  ([input]
   (let[[fst & more] input]
     (if fst [[fst] more] []))))

(defn digit?
  [c]
  (let [v (apply - (map int [c \0]))]
    (and (>= v 0) (<= v 9))))

(defn lit?
  [c]
  (fn [ch] (= c ch)))

(def asterisk? (lit? \*))
(def space? (lit? \space))

(defn em
  "Parse an <em> elements, the line should start with *."
  [line]
  (loop [[fst snd & more :as raw] line output []]
    (if (empty? output) ;not meet open em yet
      (cond
        (some nil? [fst snd]) [nil line]
        (and
          (asterisk? fst)
          (not (space? snd))) (recur (rest raw) (conj output fst))
        :else [nil line])
      (cond
        (some nil? [fst snd]) [nil line]
        (and
          (not (space? fst))
          (asterisk? snd)
          (not (asterisk? (first more)))) [(apply str (conj output fst snd)) more]
        :else (recur (rest raw) (conj output fst))))))

(defn strong
  [line]
  (loop [[fst snd thd & more :as raw] line output []]
    (if (empty? output)
      (cond
        (some nil? [fst snd thd]) [nil line]
        (and
          (every? asterisk? [fst snd])
          (not (space? thd))) (recur (drop 2 raw) (conj output fst snd))
        :else [nil line])
      (cond
        (some nil? [fst snd thd]) [nil line]
        (and
          (every? asterisk? [snd thd])
          (not (space? fst))
          (not (asterisk? (first more)))) [(apply str (conj output fst snd thd)) more]
        :else (recur (rest raw) (conj output fst))))))

(def open-bracket? (lit? \[))
(def close-bracket? (lit? \]))
(def open-parenthesis? (lit? \())
(def close-parenthesis? (lit? \)))

(defn- hyper-link-url
  [line]
  (loop [[fst & more] line output []]
    (if (empty? output)
      (cond
        (nil? fst) [nil line]
        (open-parenthesis? fst) (recur more (conj output fst))
        :else [nil line])
      (cond
        (nil? fst) [nil line]
        (close-parenthesis? fst) [(apply str (conj output fst)) more]
        :else (recur more (conj output fst))))))

(defn hyper-link
  [line]
  (loop [[fst & more] line output [] unmatch 0]
    (if (empty? output)
      (cond
        (open-bracket? fst) (recur more (conj output fst) unmatch)
        :else [nil line])
      (cond
        (nil? fst) [nil line]
        (open-bracket? fst) (recur more (conj output fst) (+ 1 unmatch))
        (close-bracket? fst) (if (or (zero? unmatch) (open-parenthesis? (first more)))
                               (let [op (hyper-link-url more)]
                                 (if (nil? (first op))
                                   [nil line]
                                   [(apply str (conj output fst (first op))) (last op)]))
                               (recur more (conj output fst) (- unmatch 1)))
        :else (recur more (conj output fst) unmatch)))))

(defn- extract-link-text
  [link]
  (loop [[fst snd & more :as input] (rest link) output []]
    (if (and (close-bracket? fst) (open-parenthesis? snd))
      (apply str output)
      (recur (rest input) (conj output fst)))))

(defn- extract-link-url
  [link]
  (let [url (drop (+ 2 (count (extract-link-text link))) link)]
    (apply str(-> url rest drop-last))))

(defn- extract-strong-text
  [text]
  (drop-last 2 (drop 2 text)))

(defn- extract-em-text
  [text]
  (apply str (-> text rest drop-last)))


(defn- mk-element
  [ele etype]
  {:type etype :value ele})

(def mk-em
  (fn [value] (mk-element value :em)))

(def mk-strong
  (fn [value] (mk-element value :strong)))

(def mk-hyper-link
  (fn [value]
    (assoc (mk-element value :hyperlink)
           :url (extract-link-url value))))

(def mk-text
  (fn [value] (mk-element value :text)))

(defn- dump [txt] (apply str txt))
(defn- parse-line'
  [line]
  (loop [[fst & more :as input] line output [] text []]
    (cond
      (nil? fst) [(conj output (mk-text (dump text))) nil]
      (asterisk? fst) (let [[p r] (strong input)]
                    (if (nil? p)
                      (let [[p1 r1] (em input)]
                        (if (nil? p1)
                          (recur more output (conj text fst))
                          (recur r1 (conj output (-> text dump mk-text) (mk-em p1)) [])))
                      (recur r (conj output (-> text dump mk-text) (mk-strong p)) [])))
      (open-bracket? fst) (let [[p r] (hyper-link input)]
                            (if (nil? p)
                              (recur more output (conj text fst))
                              (recur r (conj output (-> text dump mk-text) (mk-hyper-link p)) [])))
      :else (recur more output (conj text fst)))))


(defn- add-child
  [tree node]
  (assoc tree :value (conj (:value tree) node)))

(defn parse-line
  [line]
  (let [[eles & _] (parse-line' line)]
    (loop [[fst & more] eles tree {:type :root :value []}]
      (cond
        (nil? fst) tree
        (= :hyperlink (:type fst)) (recur more (add-child tree (assoc fst
                                                                      :value (:value
                                                                         (parse-line
                                                                           (extract-link-text
                                                                             (:value fst)))))))
        (= :strong (:type fst)) (recur more (add-child tree (assoc fst
                                                                   :value (:value
                                                                            (parse-line
                                                                              (extract-strong-text
                                                                                (:value fst)))))))
        (= :em (:type fst)) (recur more (add-child tree (assoc fst
                                                               :value (extract-em-text
                                                                                   (:value fst)))))
        :else (recur more (add-child tree fst))))))

(defn- render'
  [node html]
  (let [t (:type node)]
    (cond
      (= :strong t) (str "<strong>" html "</strong>")
      (= :em t) (str "<em>" html "</em>")
      (= :hyperlink t) (str "<a href=\"" (:url node) "\">" html "</a>")
      :else html)))

(defn render
  [node]
  (if (string? (:value node))
    (render' node (:value node))
    (let [html (reduce str "" (map render (:value node)))]
      (render' node html))))

(defn md2html
  [text]
  (render (parse-line text)))