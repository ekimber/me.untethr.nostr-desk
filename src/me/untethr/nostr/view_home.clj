(ns me.untethr.nostr.view-home
  (:require
    [cljfx.api :as fx]
    [clojure.tools.logging :as log]
    [me.untethr.nostr.domain]
    [me.untethr.nostr.links :as links]
    [me.untethr.nostr.metadata :as metadata]
    [me.untethr.nostr.style :refer [BORDER|]]
    [me.untethr.nostr.rich-text :as rich-text]
    [me.untethr.nostr.util :as util]
    [me.untethr.nostr.cache :as cache]
    [me.untethr.nostr.avatar :as avatar]
    [me.untethr.nostr.util-domain :as util-domain]
    [me.untethr.nostr.util-fx :as util-fx]
    [me.untethr.nostr.util-fx-more :as util-fx-more]
    [me.untethr.nostr.util-java :as util-java]
    [me.untethr.nostr.domain :as domain])
  (:import
    (me.untethr.nostr.domain UITextNote UITextNoteWrapper)
    (javafx.scene.layout Region HBox Priority)
    (javafx.geometry Insets Bounds)
    (javafx.scene.control ListView Hyperlink Label)
    (javafx.scene.image Image)
    (org.fxmisc.richtext GenericStyledArea)
    (java.util Optional)
    (javafx.event Event ActionEvent)
    (javafx.scene.input ScrollEvent MouseEvent)
    (javafx.scene Node)
    (javafx.stage Popup)))

(def avatar-dim 40)

(defn avatar [{:keys [picture-url]}]
  {:fx/type :image-view
   :image (cache/get* avatar/image-cache [picture-url avatar-dim])})

(defn create-content-node*
  [content]
  (let [^GenericStyledArea x (rich-text/create*)]
    (util-fx/add-style-class! x "ndesk-timeline-item-content")
    (HBox/setHgrow x Priority/ALWAYS)
    (.setWrapText x true)
    ;; @see https://github.com/FXMisc/RichTextFX/issues/674#issuecomment-429606510
    (.setAutoHeight x true)
    (.setMaxHeight x Integer/MAX_VALUE)
    (.setEditable x false)
    (.addEventFilter x
      ScrollEvent/SCROLL
      (util-java/->EventHandler
        (fn [^Event e]
          (.consume e)
          (when-let [p (.getParent x)]
            (.fireEvent p (.copyFor e (.getSource e) p))))))
    (let [found (links/detect content)]
      (loop [cursor 0 [[a b] :as found] found]
        (if a
          (do
            (rich-text/append-text! x (subs content cursor a))
            (rich-text/append-hyperlink! x (subs content a b))
            (recur b (next found)))
          (rich-text/append-text! x (subs content cursor (count content))))))
    ;; shall we not argue with this? there mere presence of this listener seems
    ;; to fix height being left rendered too short:
    (.addListener (.totalHeightEstimateProperty x)
      (util-java/->ChangeListener
        (fn [_])))
    x))

(defn timeline-item-content
  [{:keys [content]}]
  {:fx/type :h-box
   :style-class ["ndesk-timeline-item-content-outer"]
   :children [{:fx/type fx/ext-instance-factory
               :create #(create-content-node* content)}]})

(defn- show-reply-button!*
  [*state show? ^MouseEvent e]
  (let [{:keys [active-key identities]} @*state]
    (when-let [^Node target (.getTarget e)]
      (some-> target
        (.lookup ".ndesk-timeline-item-info-link")
        (.setVisible show?))
      (when (util-domain/can-publish? active-key identities)
        (some-> target
          (.lookup ".ndesk-content-controls")
          (.setVisible show?))))))

(defonce ^Popup singleton-popup
  (fx/instance
    (fx/create-component
      {:fx/type :popup
       :anchor-location :window-top-left
       :auto-hide true
       :auto-fix false
       :on-hidden (fn [_])
       :content [{:fx/type :label
                  :on-mouse-exited (fn [^MouseEvent x]
                                     (let [popup (.getWindow
                                                   (.getScene ^Node
                                                     (.getSource x)))]
                                       (.hide popup)))
                  :padding 20
                  :style {:-fx-background-color :white}
                  :effect {:fx/type :drop-shadow}
                  :text ""}]})))

(defn- ready-popup
  ^Popup [popup-width item-id]
  (let [^Label node (first (seq (.getContent singleton-popup)))]
    (.setText node (str "event-id:" item-id))
    (.setMinWidth node popup-width)
    (.setMaxWidth node popup-width)
    (.setPrefWidth node popup-width)
    singleton-popup))

(defn- show-info!
  [item-id ^ActionEvent e]
  (let [^Hyperlink node (.getSource e)
        popup-width 300
        popup (ready-popup popup-width item-id)]
    (let [bounds (.getBoundsInLocal node)
          node-pos (.localToScreen node (* 0.5 (.getWidth bounds)) 0.0)]
      (.show popup node
        (- (.getX node-pos) (* 0.5 popup-width))
        (.getY node-pos)))))

(defn timeline-item
  [{:keys [^UITextNote root-data ^UITextNote item-data metadata-cache *state]}]
  (let [item-id (:id item-data)
        pubkey (:pubkey item-data)
        pubkey-for-avatar (or (some-> pubkey (subs 0 3)) "?")
        ;pubkey-short (or (some-> pubkey util/format-pubkey-short) "?")
        timestamp (:timestamp item-data)
        content (:content item-data)
        {:keys [name about picture-url nip05-id created-at]} (some->> pubkey (metadata/get* metadata-cache))
        avatar-color (or (some-> pubkey avatar/color) :lightgray)]
    {:fx/type :border-pane
     :on-mouse-entered (partial show-reply-button!* *state true)
     :on-mouse-exited (partial show-reply-button!* *state false)
     :left (if picture-url
             {:fx/type avatar
              :picture-url picture-url}
             {:fx/type :label
              :min-width avatar-dim
              :min-height avatar-dim
              :max-width avatar-dim
              :max-height avatar-dim
              :style {:-fx-background-color avatar-color}
              :style-class "ndesk-timeline-item-photo"
              :text pubkey-for-avatar})
     :center {:fx/type :border-pane
              :top {:fx/type :border-pane
                    :border-pane/margin (Insets. 0.0 5.0 0.0 5.0)
                    :left {:fx/type :h-box
                           :children [{:fx/type :label
                                       :style-class "ndesk-timeline-item-name"
                                       :text name}
                                      {:fx/type :label
                                       :style-class "ndesk-timeline-item-pubkey"
                                       :text pubkey}]}
                    :right {:fx/type :h-box
                            :children [{:fx/type :hyperlink
                                        :style-class ["label" "ndesk-timeline-item-info-link"] ;; used for .lookup
                                        :visible false
                                        :text "info"
                                        :on-action (partial show-info! item-id)}
                                       {:fx/type :label
                                        :text (or (some-> timestamp util/format-timestamp) "?")}]}}
              :bottom {:fx/type :h-box
                       :children [{:fx/type timeline-item-content
                                   :h-box/hgrow :always
                                   :content content}
                                  {:fx/type :h-box
                                   :style-class ["ndesk-content-controls"] ;; used for .lookup
                                   :visible false
                                   :alignment :center-right
                                   :max-width Integer/MAX_VALUE
                                   :children [{:fx/type :button
                                               :style-class ["button" "ndesk-reply-button"] ;; used for .lookup
                                               :h-box/margin 3
                                               :text "reply"
                                               :on-action
                                               (fn [_]
                                                 (swap! *state assoc :active-reply-context
                                                   (domain/->UIReplyContext
                                                     (:id root-data) item-id)))}]}]}}}))

(defn- tree-rows*
  [indent ^UITextNote root-data ^UITextNote item-data metadata-cache expand? *state]
  (let [spacer-width (* indent 25)]
    (cons
      {:fx/type :h-box
       :children [{:fx/type :label
                   :min-width spacer-width
                   :max-width spacer-width
                   :text ""}
                  {:fx/type timeline-item
                   :h-box/hgrow :always
                   :spacer-width spacer-width
                   :item-data item-data
                   :root-data root-data
                   :metadata-cache metadata-cache
                   :*state *state}]}
      (when expand?
        (mapcat #(tree-rows* (inc indent) root-data % metadata-cache expand? *state) (:children item-data))))))

(defn- find-note
  [^UITextNote note pred]
  (if (pred note) note (first (map #(find-note % pred) (:children note)))))

(defn- tree* [{:keys [^UITextNoteWrapper note-wrapper metadata-cache *state]}]
  ;; note: we get nil note-wrapper sometimes when the list-cell is advancing
  ;; in some ways -- for now just render label w/ err which we'll see if
  ;; this matters --
  (if (nil? note-wrapper)
    {:fx/type :label :text "err"}
    (let [{:keys [root expanded? max-timestamp note-count]} note-wrapper]
      {:fx/type :v-box
       :children
       (vec
         (concat
           (tree-rows*
             0
             root
             (if expanded?
               root
               (or
                 (find-note root
                   #(= (:timestamp %) max-timestamp))
                 ;; should never get:
                 root))
             metadata-cache
             expanded?
             *state)
           ;; this is a bad experience so far so we disable collapse altogether for now
           #_(when (> note-count 1)
               [{:fx/type :hyperlink
                 :text (if expanded? "collapse" (format "expand (%d notes)" note-count))
                 :on-action (fn [_]
                              (timeline/toggle! *state (-> note-wrapper :root :id)))}])))})))

(defn home [{:keys [metadata-cache *state]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created #(.setSelectionModel % util-fx-more/no-selection-model)
   :desc {:fx/type :list-view
          :focus-traversable false
          :cell-factory {:fx/cell-type :list-cell
                         :describe (fn [note-wrapper]
                                     {:graphic
                                      {:fx/type tree*
                                       :note-wrapper note-wrapper
                                       :metadata-cache metadata-cache
                                       :*state *state}})}}})

(defn create-list-view
  ^ListView [*state metadata-cache _executor]
  (fx/instance
    (fx/create-component {:fx/type home
                          :metadata-cache metadata-cache
                          :*state *state})))
