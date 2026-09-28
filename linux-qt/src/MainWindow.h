// The editor window: one note on a canvas, a toolbar, and file commands.
#pragma once

#include "xnotes.h"

#include <QActionGroup>
#include <QMainWindow>
#include <QTemporaryDir>

#include <memory>

class CanvasWidget;
class QLabel;

class MainWindow : public QMainWindow {
    Q_OBJECT
public:
    MainWindow();
    ~MainWindow() override;

    /* Open path; on failure shows why and keeps the current note. */
    bool open(const QString& path);
    void newNote();

protected:
    void closeEvent(QCloseEvent* e) override;

private:
    CanvasWidget* canvas_;
    xn_note* note_ = nullptr;
    std::unique_ptr<QTemporaryDir> workDir_;
    QString path_;
    QAction* undo_;
    QAction* redo_;
    QAction* colorAction_;
    QActionGroup* tools_;
    QLabel* pageLabel_;
    QLabel* zoomLabel_;
    bool dark_ = true;

    void install(xn_note* note, std::unique_ptr<QTemporaryDir> workDir, const QString& path);
    void buildUi();
    void refresh();
    void refreshView();
    void chooseOpen();
    bool save(bool askPath);
    bool confirmDiscard();
    void chooseColor();
    void setInk(const QColor& c);
};
