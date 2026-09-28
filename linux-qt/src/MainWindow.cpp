#include "MainWindow.h"

#include "CanvasWidget.h"
#include "SelectionBar.h"

#include <QApplication>
#include <QCloseEvent>
#include <QColorDialog>
#include <QDoubleSpinBox>
#include <QMenu>
#include <QToolButton>
#include <QFileDialog>
#include <QFileInfo>
#include <QLabel>
#include <QMessageBox>
#include <QPainter>
#include <QPixmap>
#include <QStatusBar>
#include <QTimer>
#include <QToolBar>

namespace {

struct ToolEntry {
    const char* id;
    const char* label;
    const char* key;
};

const ToolEntry kTools[] = {
    {"pan", "Рука", "1"},
    {"pen", "Перо", "2"},
    {"highlighter", "Маркер", "3"},
    {"eraser", "Ластик", "4"},
    {"lasso", "Лассо", "5"},
    {"select", "Выделение", "6"},
    {"shape", "Фигура", "7"},
};

struct ShapeEntry {
    const char* id;
    const char* label;
};

const ShapeEntry kShapes[] = {
    {"line", "Линия"}, {"arrow", "Стрелка"}, {"rectangle", "Прямоугольник"},
    {"ellipse", "Эллипс"}, {"circle", "Круг"}, {"triangle", "Треугольник"},
};

/* The app's ink presets (InkPalette) plus black and blue for light paper. */
const QList<QColor> kSwatches = {
    QColor(0, 230, 118), QColor(236, 236, 236), QColor(255, 92, 92), QColor(255, 199, 64),
    QColor(88, 196, 255), QColor(199, 134, 255), QColor(128, 128, 128), QColor(28, 28, 28), QColor(33, 150, 243),
};

/* Tools whose ink width the toolbar sets. */
bool hasWidth(const QByteArray& tool) { return tool == "pen" || tool == "highlighter" || tool == "shape"; }

QString takeError(char* error) {
    QString message = error ? QString::fromUtf8(error) : QStringLiteral("неизвестная ошибка");
    xn_free_string(error);
    return message;
}

}  // namespace

MainWindow::MainWindow() {
    canvas_ = new CanvasWidget(this);
    setCentralWidget(canvas_);
    buildUi();
    connect(canvas_, &CanvasWidget::contentChanged, this, &MainWindow::refresh);
    connect(canvas_, &CanvasWidget::viewChanged, this, &MainWindow::refreshView);
    connect(canvas_, &CanvasWidget::notice, this, [this](int code) {
        statusBar()->showMessage(code == XN_NOTICE_KEEP_ONE_PAGE ? QStringLiteral("В заметке должна остаться хотя бы одна страница")
                                 : code == XN_NOTICE_PAGE_ALREADY_EMPTY ? QStringLiteral("Страница уже пуста")
                                                                        : QString(), 4000);
    });
    // Input and speed, for checking a tablet: whether Qt reports it as a pen with pressure.
    inputLabel_ = new QLabel(this);
    statusBar()->addPermanentWidget(inputLabel_);
    auto* stats = new QTimer(this);
    connect(stats, &QTimer::timeout, this, [this] {
        const QString input = canvas_->lastInput();
        inputLabel_->setText((input.isEmpty() ? QString() : QStringLiteral("ввод: %1, давление %2   ").arg(input).arg(canvas_->lastPressure(), 0, 'f', 2)) +
                             QStringLiteral("кадр %1 мс").arg(canvas_->lastFrameMs(), 0, 'f', 1));
    });
    stats->start(250);
    resize(1200, 900);
}

MainWindow::~MainWindow() {
    canvas_->setNote(nullptr);
    if (note_) xn_note_close(note_);
}

void MainWindow::buildUi() {
    QToolBar* bar = addToolBar(QStringLiteral("Основное"));
    bar->setMovable(false);
    bar->addAction(QStringLiteral("Открыть…"), QKeySequence::Open, this, &MainWindow::chooseOpen);
    bar->addAction(QStringLiteral("Сохранить"), QKeySequence::Save, this, [this] { save(false); });
    bar->addAction(QStringLiteral("Сохранить как…"), QKeySequence::SaveAs, this, [this] { save(true); });
    bar->addSeparator();

    undo_ = bar->addAction(QStringLiteral("↶"), QKeySequence::Undo, this, [this] { if (canvas_->editor()) xn_editor_undo(canvas_->editor()); });
    undo_->setToolTip(QStringLiteral("Отменить (Ctrl+Z)"));
    redo_ = bar->addAction(QStringLiteral("↷"), this, [this] { if (canvas_->editor()) xn_editor_redo(canvas_->editor()); });
    redo_->setShortcuts({QKeySequence(Qt::CTRL | Qt::SHIFT | Qt::Key_Z), QKeySequence(Qt::CTRL | Qt::Key_Y)});
    redo_->setToolTip(QStringLiteral("Повторить (Ctrl+Shift+Z)"));
    bar->addSeparator();

    bar->addAction(QStringLiteral("←"), QKeySequence(Qt::Key_PageUp), this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_go_to_page(e, std::max(0, xn_editor_current_page(e) - 1));
    });
    pageLabel_ = new QLabel(this);
    pageLabel_->setMinimumWidth(60);
    pageLabel_->setAlignment(Qt::AlignCenter);
    bar->addWidget(pageLabel_);
    bar->addAction(QStringLiteral("→"), QKeySequence(Qt::Key_PageDown), this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_go_to_page(e, std::min(xn_note_page_count(note_) - 1, xn_editor_current_page(e) + 1));
    });
    bar->addAction(QStringLiteral("−"), QKeySequence::ZoomOut, this, [this] { if (auto* e = canvas_->editor()) xn_editor_zoom_step(e, 0); });
    zoomLabel_ = new QLabel(this);
    zoomLabel_->setMinimumWidth(50);
    zoomLabel_->setAlignment(Qt::AlignCenter);
    bar->addWidget(zoomLabel_);
    bar->addAction(QStringLiteral("+"), QKeySequence(Qt::CTRL | Qt::Key_Equal), this, [this] { if (auto* e = canvas_->editor()) xn_editor_zoom_step(e, 1); });
    bar->addAction(QStringLiteral("По ширине"), this, [this] { if (auto* e = canvas_->editor()) xn_editor_fit_width(e); });
    bar->addAction(QStringLiteral("+ Страница"), this, [this] { if (auto* e = canvas_->editor()) xn_editor_add_page(e); });
    bar->addAction(QStringLiteral("− Страница"), this, [this] {
        if (!canvas_->editor()) return;
        if (QMessageBox::question(this, QStringLiteral("Удалить страницу"), QStringLiteral("Удалить текущую страницу?")) == QMessageBox::Yes)
            xn_editor_delete_current_page(canvas_->editor());
    });
    bar->addSeparator();
    QAction* dark = bar->addAction(QStringLiteral("Тёмная"));
    dark->setCheckable(true);
    dark->setChecked(dark_);
    connect(dark, &QAction::toggled, this, [this](bool on) {
        dark_ = on;
        canvas_->setDark(on);
    });

    // Second row: tools and their style.
    addToolBarBreak();
    QToolBar* tools = addToolBar(QStringLiteral("Инструменты"));
    tools->setMovable(false);
    tools_ = new QActionGroup(this);
    for (const ToolEntry& t : kTools) {
        QAction* a = tools->addAction(QString::fromUtf8(t.label));
        a->setCheckable(true);
        a->setShortcut(QKeySequence(QString::fromLatin1(t.key)));
        a->setData(QByteArray(t.id));
        tools_->addAction(a);
        if (qstrcmp(t.id, "pen") == 0) a->setChecked(true);
        if (qstrcmp(t.id, "shape") == 0) {
            // The shape tool's button also opens what it draws.
            auto* menu = new QMenu(this);
            auto* kinds = new QActionGroup(menu);
            for (const ShapeEntry& k : kShapes) {
                QAction* ka = menu->addAction(QString::fromUtf8(k.label));
                ka->setCheckable(true);
                ka->setChecked(shapeKind_ == k.id);
                kinds->addAction(ka);
                connect(ka, &QAction::triggered, this, [this, a, k] {
                    shapeKind_ = k.id;
                    applyShape();
                    a->trigger();
                });
            }
            menu->addSeparator();
            QAction* fill = menu->addAction(QStringLiteral("Заливка"));
            fill->setCheckable(true);
            connect(fill, &QAction::toggled, this, [this](bool on) { shapeFill_ = on; applyShape(); });
            QAction* dashed = menu->addAction(QStringLiteral("Пунктир"));
            dashed->setCheckable(true);
            connect(dashed, &QAction::toggled, this, [this](bool on) { shapeDashed_ = on; applyShape(); });
            a->setMenu(menu);
            if (auto* button = qobject_cast<QToolButton*>(tools->widgetForAction(a))) button->setPopupMode(QToolButton::MenuButtonPopup);
        }
    }
    connect(tools_, &QActionGroup::triggered, this, [this](QAction* a) {
        canvas_->setTool(a->data().toByteArray().constData());
        syncWidth();
    });
    tools->addSeparator();

    tools->addWidget(new QLabel(QStringLiteral(" Толщина "), this));
    width_ = new QDoubleSpinBox(this);
    width_->setRange(XN_STYLE_MIN_WIDTH, XN_STYLE_MAX_WIDTH);
    width_->setSingleStep(0.5);
    width_->setDecimals(1);
    width_->setFocusPolicy(Qt::ClickFocus);
    connect(width_, &QDoubleSpinBox::valueChanged, this, [this](double w) {
        const QByteArray tool = currentTool();
        if (!hasWidth(tool) || syncingWidth_) return;
        widths_[tool] = w;
        if (tool == "shape") applyShape();
        else if (auto* e = canvas_->editor()) xn_editor_set_tool_width(e, tool.constData(), w);
    });
    tools->addWidget(width_);
    tools->addSeparator();

    for (const QColor& c : kSwatches) {
        QAction* a = tools->addAction(swatchIcon(c), QString());
        a->setToolTip(c.name());
        connect(a, &QAction::triggered, this, [this, c] { setInk(c); });
    }
    colorAction_ = tools->addAction(QStringLiteral("Цвет…"), this, &MainWindow::chooseColor);
    colorAction_->setToolTip(QStringLiteral("Текущий цвет; нажмите, чтобы выбрать другой"));

    selectionBar_ = new SelectionBar(canvas_);
    selectionBar_->setSwatches(kSwatches);
    connect(canvas_, &CanvasWidget::selectionMenu, this, [this](bool shown, QRectF) { selectionBar_->onMenu(shown); });
    connect(canvas_, &CanvasWidget::viewChanged, selectionBar_, &SelectionBar::refresh);
    connect(canvas_, &CanvasWidget::contentChanged, selectionBar_, &SelectionBar::refresh);
    connect(canvas_, &CanvasWidget::contextMenu, this, &MainWindow::showContextMenu);
    connect(canvas_, &CanvasWidget::toolChanged, this, [this](const QString& id) {
        for (QAction* a : tools_->actions())
            if (a->data().toByteArray() == id.toUtf8()) a->setChecked(true);
        syncWidth();
    });

    // Canvas commands without buttons.
    auto bind = [this](const QKeySequence& key, auto fn) {
        auto* a = new QAction(this);
        a->setShortcut(key);
        connect(a, &QAction::triggered, this, fn);
        addAction(a);
    };
    bind(QKeySequence(Qt::Key_Escape), [this] { if (canvas_->editor()) xn_editor_escape(canvas_->editor()); });
    bind(QKeySequence(Qt::Key_Delete), [this] { if (canvas_->editor()) xn_editor_delete_selection(canvas_->editor()); });
    bind(QKeySequence::New, [this] { if (confirmDiscard()) newNote(); });
    bind(QKeySequence::Copy, [this] { if (canvas_->editor()) xn_editor_copy(canvas_->editor()); });
    bind(QKeySequence::Cut, [this] { if (canvas_->editor()) xn_editor_cut(canvas_->editor()); });
    bind(QKeySequence::Paste, [this] { pasteAt(canvas_->mapFromGlobal(QCursor::pos())); });
    bind(QKeySequence(Qt::CTRL | Qt::Key_D), [this] { if (canvas_->editor()) xn_editor_duplicate(canvas_->editor()); });
    bind(QKeySequence::SelectAll, [this] { selectAll(); });
    bind(QKeySequence::Quit, [this] { close(); });
    setInk(kSwatches.first());
    syncWidth();
}

QByteArray MainWindow::currentTool() const {
    QAction* a = tools_->checkedAction();
    return a ? a->data().toByteArray() : QByteArray();
}

void MainWindow::syncWidth() {
    const QByteArray tool = currentTool();
    width_->setEnabled(hasWidth(tool));
    if (!hasWidth(tool)) return;
    double w = widths_.value(tool, 0);
    if (w <= 0) w = tool == "shape" ? 3.0 : canvas_->editor() ? xn_editor_tool_width(canvas_->editor(), tool.constData()) : 3.0;
    syncingWidth_ = true;
    width_->setValue(w);
    syncingWidth_ = false;
}

void MainWindow::applyShape() {
    auto* e = canvas_->editor();
    if (!e) return;
    const xn_shape_style style{shapeKind_.constData(), widths_.value("shape", 3.0), shapeFill_ ? 1 : 0, 0.25, shapeDashed_ ? 1 : 0};
    xn_editor_set_shape_style(e, &style);
}

/* Settings the toolbar holds, handed to a fresh editor. */
void MainWindow::applyToolSettings() {
    auto* e = canvas_->editor();
    if (!e) return;
    for (auto it = widths_.cbegin(); it != widths_.cend(); ++it)
        if (it.key() != "shape") xn_editor_set_tool_width(e, it.key().constData(), it.value());
    applyShape();
}

void MainWindow::pasteAt(QPointF widget) {
    auto* e = canvas_->editor();
    if (!e || !xn_editor_can_paste(e)) return;
    if (!canvas_->rect().contains(widget.toPoint())) widget = QPointF(canvas_->width() / 2.0, canvas_->height() / 3.0);
    const QPointF at = canvas_->toDevice(widget);
    xn_editor_paste_at(e, at.x(), at.y());
    selectionBar_->refresh();
}

void MainWindow::selectAll() {
    auto* e = canvas_->editor();
    if (!e) return;
    xn_editor_select_all(e);
    selectionBar_->refresh();
}

void MainWindow::showContextMenu(QPointF at, bool onLocked) {
    auto* e = canvas_->editor();
    if (!e) return;
    QMenu menu(this);
    QAction* paste = menu.addAction(QStringLiteral("Вставить"), this, [this, at] { pasteAt(at); });
    paste->setEnabled(xn_editor_can_paste(e));
    if (onLocked) menu.addAction(QStringLiteral("Открепить"), this, [e] { xn_editor_unlock_pressed(e); });
    menu.addAction(QStringLiteral("Выделить всё"), this, &MainWindow::selectAll);
    menu.exec(canvas_->mapToGlobal(at.toPoint()));
}

void MainWindow::install(xn_note* note, std::unique_ptr<QTemporaryDir> workDir, const QString& path) {
    canvas_->setNote(nullptr);
    if (note_) xn_note_close(note_);
    note_ = note;
    workDir_ = std::move(workDir);
    path_ = path;
    canvas_->setNote(note_);
    canvas_->setDark(dark_);
    if (QAction* tool = tools_->checkedAction()) canvas_->setTool(tool->data().toByteArray().constData());
    if (colorAction_->data().isValid()) setInk(colorAction_->data().value<QColor>());
    applyToolSettings();
    syncWidth();
    refresh();
    refreshView();
}

void MainWindow::newNote() { install(xn_note_new(1), nullptr, QString()); }

bool MainWindow::open(const QString& path) {
    auto workDir = std::make_unique<QTemporaryDir>();
    if (!workDir->isValid()) {
        QMessageBox::warning(this, QStringLiteral("Открыть"), QStringLiteral("Не удалось создать временную папку"));
        return false;
    }
    char* error = nullptr;
    const QByteArray file = QFile::encodeName(QFileInfo(path).absoluteFilePath());
    const QByteArray dir = QFile::encodeName(workDir->path());
    xn_note* note = xn_note_open(file.constData(), dir.constData(), canvas_->host(), &error);
    if (!note) {
        QMessageBox::warning(this, QStringLiteral("Открыть"), QStringLiteral("Не удалось открыть %1:\n%2").arg(path, takeError(error)));
        return false;
    }
    install(note, std::move(workDir), QFileInfo(path).absoluteFilePath());
    return true;
}

void MainWindow::chooseOpen() {
    if (!confirmDiscard()) return;
    const QString path = QFileDialog::getOpenFileName(this, QStringLiteral("Открыть заметку"), QFileInfo(path_).absolutePath(),
                                                      QStringLiteral("Заметки xnotes (*.xnote)"));
    if (!path.isEmpty()) open(path);
}

bool MainWindow::save(bool askPath) {
    if (!note_) return false;
    QString path = path_;
    if (askPath || path.isEmpty()) {
        path = QFileDialog::getSaveFileName(this, QStringLiteral("Сохранить заметку"), path.isEmpty() ? QStringLiteral("note.xnote") : path,
                                            QStringLiteral("Заметки xnotes (*.xnote)"));
        if (path.isEmpty()) return false;
        if (!path.endsWith(QLatin1String(".xnote"))) path += QLatin1String(".xnote");
    }
    char* error = nullptr;
    if (!xn_note_save(note_, QFile::encodeName(path).constData(), canvas_->host(), &error)) {
        QMessageBox::warning(this, QStringLiteral("Сохранить"), QStringLiteral("Не удалось сохранить %1:\n%2").arg(path, takeError(error)));
        return false;
    }
    path_ = path;
    statusBar()->showMessage(QStringLiteral("Сохранено: %1").arg(path), 3000);
    refresh();
    return true;
}

bool MainWindow::confirmDiscard() {
    if (!note_ || !xn_note_is_dirty(note_)) return true;
    const auto answer = QMessageBox::question(this, QStringLiteral("Несохранённые изменения"),
                                              QStringLiteral("Сохранить изменения в заметке?"),
                                              QMessageBox::Save | QMessageBox::Discard | QMessageBox::Cancel);
    if (answer == QMessageBox::Save) return save(false);
    return answer == QMessageBox::Discard;
}

void MainWindow::closeEvent(QCloseEvent* e) {
    if (confirmDiscard()) e->accept(); else e->ignore();
}

void MainWindow::chooseColor() {
    const QColor c = QColorDialog::getColor(colorAction_->data().value<QColor>(), this, QStringLiteral("Цвет чернил"));
    if (c.isValid()) setInk(c);
}

void MainWindow::setInk(const QColor& c) {
    colorAction_->setData(c);
    colorAction_->setIcon(swatchIcon(c));
    if (auto* e = canvas_->editor())
        xn_editor_set_ink_color(e, (xn_rgba(c.red()) << 24) | (xn_rgba(c.green()) << 16) | (xn_rgba(c.blue()) << 8) | 0xFF);
}

void MainWindow::refresh() {
    auto* e = canvas_->editor();
    undo_->setEnabled(e && xn_editor_can_undo(e));
    redo_->setEnabled(e && xn_editor_can_redo(e));
    const QString name = path_.isEmpty() ? QStringLiteral("Новая заметка") : QFileInfo(path_).fileName();
    const bool dirty = note_ && xn_note_is_dirty(note_);
    setWindowTitle((dirty ? QStringLiteral("• ") : QString()) + name + QStringLiteral(" — xnotes"));
    refreshView();
}

void MainWindow::refreshView() {
    auto* e = canvas_->editor();
    if (!e || !note_) return;
    pageLabel_->setText(QStringLiteral("%1 / %2").arg(xn_editor_current_page(e) + 1).arg(xn_note_page_count(note_)));
    zoomLabel_->setText(QStringLiteral("%1%").arg(int(xn_editor_zoom(e) * 100)));
}
