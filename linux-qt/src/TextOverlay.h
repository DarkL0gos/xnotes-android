// The in-place editor of a text box, over the canvas where the box is (the core leaves the box off
// the canvas while it is open).
//
// The text is laid out in page pixels with the page's font and drawn scaled by the zoom, the way
// the box is drawn on the page, so lines break at the same words while typing and after. Editing
// itself (caret, selection, undo) is a QTextDocument and its cursor.
#pragma once

#include "xnotes.h"

#include <QFont>
#include <QTextCursor>
#include <QTextDocument>
#include <QTextLayout>
#include <QWidget>

#include <memory>

class CanvasWidget;

class TextOverlay : public QWidget {
    Q_OBJECT
public:
    explicit TextOverlay(CanvasWidget* canvas);

    /* The core opened (field) or closed (nullptr) the edit. */
    void onTextEdit(const xn_text_field* field);
    /* Follow the box after a scroll or zoom. */
    void reposition();

    QString text() const { return doc_.toPlainText(); }
    void selectAll();

protected:
    bool event(QEvent* e) override;
    void paintEvent(QPaintEvent* e) override;
    void keyPressEvent(QKeyEvent* e) override;
    void mousePressEvent(QMouseEvent* e) override;
    void mouseMoveEvent(QMouseEvent* e) override;
    void mouseDoubleClickEvent(QMouseEvent* e) override;
    void inputMethodEvent(QInputMethodEvent* e) override;
    QVariant inputMethodQuery(Qt::InputMethodQuery query) const override;
    void focusOutEvent(QFocusEvent* e) override;

private:
    CanvasWidget* canvas_;
    QTextDocument doc_;
    QTextCursor cursor_;
    QTextLayout layout_;
    QFont font_;              // page pixels
    QString face_;
    xn_text_field field_{};   // numbers only
    double scale_ = 1;        // page px → widget px
    double layoutHeight_ = 0; // page px
    bool syncing_ = false;
    bool caretOn_ = true;
    int blinkTimer_ = 0;

    void place();
    void relayout();
    void changed();
    int hitTest(QPointF widget) const;
    QRectF caretRect() const;  // widget px
    void moveVertically(int lines, QTextCursor::MoveMode mode);
    void restartBlink();
    void timerEvent(QTimerEvent* e) override;
};
